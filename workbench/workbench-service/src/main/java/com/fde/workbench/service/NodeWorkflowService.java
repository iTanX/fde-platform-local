package com.fde.workbench.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.FileRecord;
import com.fde.workbench.dao.entity.NodeInputFile;
import com.fde.workbench.dao.entity.NodeOutputRecord;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.entity.RequirementNode;
import com.fde.workbench.dao.entity.RequirementWithStats;
import com.fde.workbench.dao.entity.WorkflowNode;
import com.fde.workbench.dao.mapper.FileMapper;
import com.fde.workbench.dao.mapper.NodeInputMapper;
import com.fde.workbench.dao.mapper.NodeOutputMapper;
import com.fde.workbench.dao.mapper.RequirementMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.bo.InternalOperator;
import com.fde.workbench.service.bo.NodeReferenceCommand;
import com.fde.workbench.service.security.CurrentUserContext;
import com.fde.workbench.service.vo.NodeContextVO;
import com.fde.workbench.service.vo.NodeInputUploadVO;
import com.fde.workbench.service.vo.NodeSnapshots;
import com.fde.workbench.service.vo.RequirementNodeVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 节点信息、输入上传 / 删除、节点上下文与引用登记（docs/api.md §8、§11 context/reference；
 * PRD REQ-F4/F5/H5、AC-F4.1~F4.7、AC-F5.1、AC-H5.1/H5.2）。
 * <p>
 * <b>本类负责"读 + 输入侧"，输出提交与推进在 {@link NodeAdvanceService}</b>：
 * 两者都要解析快照、定位节点、判定只读，因此这些公共能力放在这里，
 * 推进服务通过包内方法与构造注入复用，不另写一套。
 * <p>
 * <b>只读判定（{@code readonly}，api.md §8 三种情况，任一成立即为 true）</b>：
 * <ol>
 *   <li>不是当前节点（{@code status != current}）——PRD AC-F5.1：非当前节点不显示会话页签与输入框；</li>
 *   <li>需求创建人不是当前用户，且当前用户也不是项目 owner——AC-H5.1：他人的需求一律只读；</li>
 *   <li>需求状态不是 {@code in_progress}（已完成）——AC-F4.6 之后所有节点都只读。</li>
 * </ol>
 * 注意判定顺序：先"不存在 / 非成员"（404 / 403）再谈只读，非成员看不到任何节点数据。
 */
@Service
public class NodeWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(NodeWorkflowService.class);

    /** 原始文件名（存库 {@code wb_file.original_name VARCHAR(255)}）长度上限 */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /** 原始文件名截断到该长度以下（给 fileId 前缀留出余量） */
    private static final int MAX_SAFE_FILE_NAME_LENGTH = 200;

    /** 占位摘要（落盘前 {@code wb_file.sha256} 是 NOT NULL，先用全 0 占位，写完立刻回填） */
    private static final String ZERO_SHA256 = "0".repeat(64);

    /** 上下文 / 快照解析用的 Jackson 视图：忽略未知字段，让旧快照在新字段加入后仍可读 */
    private static final TypeReference<List<NodeSnapshots.InputItem>> INPUT_ITEMS =
            new TypeReference<>() {
            };

    private static final TypeReference<List<NodeSnapshots.OutputItem>> OUTPUT_ITEMS =
            new TypeReference<>() {
            };

    private static final TypeReference<List<NodeSnapshots.SkillItem>> SKILL_ITEMS =
            new TypeReference<>() {
            };

    private final RequirementMapper requirementMapper;

    private final NodeInputMapper nodeInputMapper;

    private final NodeOutputMapper nodeOutputMapper;

    private final FileMapper fileMapper;

    private final ProjectAccessService projectAccessService;

    private final CurrentUserContext currentUserContext;

    private final WorkbenchStorage storage;

    private final NodeStoragePaths nodeStoragePaths;

    private final FileDigestService fileDigestService;

    private final NodeAdvanceEvaluator advanceEvaluator;

    private final SessionService sessionService;

    private final WorkbenchStorageProperties properties;

    private final TransactionTemplate transactionTemplate;

    /** 快照 JSON 的解析器（读取方向；写入方向仍在 RequirementService） */
    private final ObjectMapper snapshotMapper;

    public NodeWorkflowService(RequirementMapper requirementMapper,
                               NodeInputMapper nodeInputMapper,
                               NodeOutputMapper nodeOutputMapper,
                               FileMapper fileMapper,
                               ProjectAccessService projectAccessService,
                               CurrentUserContext currentUserContext,
                               WorkbenchStorage storage,
                               NodeStoragePaths nodeStoragePaths,
                               FileDigestService fileDigestService,
                               NodeAdvanceEvaluator advanceEvaluator,
                               SessionService sessionService,
                               WorkbenchStorageProperties properties,
                               TransactionTemplate transactionTemplate,
                               ObjectMapper objectMapper) {
        this.requirementMapper = requirementMapper;
        this.nodeInputMapper = nodeInputMapper;
        this.nodeOutputMapper = nodeOutputMapper;
        this.fileMapper = fileMapper;
        this.projectAccessService = projectAccessService;
        this.currentUserContext = currentUserContext;
        this.storage = storage;
        this.nodeStoragePaths = nodeStoragePaths;
        this.fileDigestService = fileDigestService;
        this.advanceEvaluator = advanceEvaluator;
        this.sessionService = sessionService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        // 解析快照时忽略未知字段：快照是历史数据，后续 record 加字段不能让读取方 500
        this.snapshotMapper = objectMapper.copy()
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ------------------------------------------------------------------
    // 节点信息页
    // ------------------------------------------------------------------

    /**
     * 节点信息页数据（项目成员即可访问，是否可操作见 {@code readonly}）。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @return 节点信息视图
     * @throws BizException 需求 / 节点不存在 404/10301；非成员 403/10201
     */
    public RequirementNodeVO detail(Long requirementId, Long nodeId) {
        NodeAccess access = requireMemberAccess(requirementId, nodeId);
        RequirementNode node = access.node();
        NodeSnapshots.NodeSnapshot snapshot = resolveSnapshot(node);

        List<RequirementNodeVO.SkillVO> skills = snapshot.skills().stream()
                .map(item -> new RequirementNodeVO.SkillVO(item.skillId(), item.name(), item.versionNo()))
                .toList();

        List<RequirementNodeVO.InputVO> inputs = buildInputs(node, snapshot);
        List<RequirementNodeVO.OutputVO> outputs = buildOutputs(node, snapshot);
        RequirementNodeVO.PreviousNodeVO previousNode = buildPreviousNode(access.requirement(), node);
        String defaultSessionId = sessionService.resolveDefaultSessionId(requirementId, node);

        return new RequirementNodeVO(node.getId(), node.getSeq(), node.getName(), node.getDescription(),
                node.getStatus(), node.getInputType(), skills, inputs, outputs, previousNode,
                access.readonly(), defaultSessionId);
    }

    /**
     * 推进预览：还差哪些必填项（权限 C）。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @return 预览
     * @throws BizException 404/10301；403/10201；403/10202
     */
    public com.fde.workbench.service.vo.AdvancePreviewVO advancePreview(Long requirementId, Long nodeId) {
        NodeAccess access = requireOperableAccess(requirementId, nodeId);
        NodeSnapshots.NodeSnapshot snapshot = resolveSnapshot(access.node());
        return advanceEvaluator.preview(access.node().getId(), access.node().getInputType(), snapshot);
    }

    /**
     * 组装输入区：固定输入项逐项挂各自已上传文件；{@code free} 节点返回自由输入文件列表
     * （用一个 {@code itemId=null} 的虚拟项承载，前端按 {@code inputType} 决定渲染方式）。
     *
     * @param node     节点实例
     * @param snapshot 快照
     * @return 输入视图列表
     */
    private List<RequirementNodeVO.InputVO> buildInputs(RequirementNode node, NodeSnapshots.NodeSnapshot snapshot) {
        List<NodeInputFile> files = nodeInputMapper.selectByNode(node.getId());
        List<RequirementNodeVO.InputVO> result = new ArrayList<>();
        if (WorkflowNode.INPUT_TYPE_FREE.equals(node.getInputType())) {
            // free：所有上传的 input_item_id 都是 NULL，作为一个整体列表挂在一个 itemId=null 的项上
            List<RequirementNodeVO.FileVO> freeFiles = files.stream()
                    .filter(file -> file.getInputItemId() == null)
                    .map(this::toInputFileVO)
                    .toList();
            result.add(new RequirementNodeVO.InputVO(null, null, null, false, freeFiles));
            return result;
        }
        Map<Long, List<RequirementNodeVO.FileVO>> filesByItem = new LinkedHashMap<>();
        for (NodeInputFile file : files) {
            if (file.getInputItemId() != null) {
                filesByItem.computeIfAbsent(file.getInputItemId(), ignored -> new ArrayList<>())
                        .add(toInputFileVO(file));
            }
        }
        for (NodeSnapshots.InputItem item : snapshot.inputs()) {
            result.add(new RequirementNodeVO.InputVO(item.itemId(), item.name(), item.fileKind(),
                    Boolean.TRUE.equals(item.required()),
                    filesByItem.getOrDefault(item.itemId(), List.of())));
        }
        return result;
    }

    /**
     * 组装输出区：快照里的每个输出项 + 当前版本（未提交为 {@code submitted=false}）。
     *
     * @param node     节点实例
     * @param snapshot 快照
     * @return 输出视图列表（顺序与快照一致）
     */
    private List<RequirementNodeVO.OutputVO> buildOutputs(RequirementNode node, NodeSnapshots.NodeSnapshot snapshot) {
        Map<String, NodeOutputRecord> records = new LinkedHashMap<>();
        for (NodeOutputRecord record : nodeOutputMapper.selectByNode(node.getId())) {
            records.put(record.getItemKey(), record);
        }
        return snapshot.outputs().stream().map(item -> {
            NodeOutputRecord record = records.get(item.itemKey());
            if (record == null) {
                return new RequirementNodeVO.OutputVO(item.itemKey(), item.name(), item.fileKind(),
                        Boolean.TRUE.equals(item.required()), false, null, null, null);
            }
            RequirementNodeVO.FileVO file = new RequirementNodeVO.FileVO(record.getFileId(),
                    record.getOriginalName(), record.getSizeBytes(),
                    RequirementNodeVO.toOffset(record.getCommittedAt()), null, null);
            return new RequirementNodeVO.OutputVO(item.itemKey(), item.name(), item.fileKind(),
                    Boolean.TRUE.equals(item.required()), true, record.getVersionNo(),
                    RequirementNodeVO.toOffset(record.getCommittedAt()), file);
        }).toList();
    }

    /**
     * 上一节点输出：{@code seq-1} 节点的输出项与已提交文件；第一个节点返回 {@code null}（api.md §8）。
     *
     * @param requirement 需求
     * @param node        当前查看的节点
     * @return 上一节点视图；不存在返回 null
     */
    private RequirementNodeVO.PreviousNodeVO buildPreviousNode(RequirementWithStats requirement,
                                                               RequirementNode node) {
        if (node.getSeq() == null || node.getSeq() <= 1) {
            return null;
        }
        RequirementNode previous = requirementMapper.selectNodesByRequirement(requirement.getId()).stream()
                .filter(candidate -> Objects.equals(candidate.getSeq(), node.getSeq() - 1))
                .findFirst()
                .orElse(null);
        if (previous == null) {
            return null;
        }
        Map<String, NodeOutputRecord> records = new LinkedHashMap<>();
        for (NodeOutputRecord record : nodeOutputMapper.selectByNode(previous.getId())) {
            records.put(record.getItemKey(), record);
        }
        List<RequirementNodeVO.PreviousOutputVO> outputs = resolveSnapshot(previous).outputs().stream()
                .map(item -> {
                    NodeOutputRecord record = records.get(item.itemKey());
                    RequirementNodeVO.FileVO file = record == null ? null
                            : new RequirementNodeVO.FileVO(record.getFileId(), record.getOriginalName(),
                            record.getSizeBytes(), RequirementNodeVO.toOffset(record.getCommittedAt()),
                            null, null);
                    return new RequirementNodeVO.PreviousOutputVO(item.itemKey(), item.name(), file);
                })
                .toList();
        return new RequirementNodeVO.PreviousNodeVO(previous.getSeq(), previous.getName(), outputs);
    }

    private RequirementNodeVO.FileVO toInputFileVO(NodeInputFile file) {
        return new RequirementNodeVO.FileVO(file.getFileId(), file.getOriginalName(), file.getSizeBytes(),
                RequirementNodeVO.toOffset(file.getCreatedAt()), file.getId(), file.getInputItemId());
    }

    // ------------------------------------------------------------------
    // 上传 / 删除输入
    // ------------------------------------------------------------------

    /**
     * 上传节点输入（权限 C，且节点为当前节点、需求进行中）。
     * <p>
     * 事务边界（与 {@code SkillService} 同一套思路）：
     * <ol>
     *   <li>校验 + 落盘（写临时文件、算 sha256、原子 move 到 {@code inputs/<seq>/<fileId>_<原名>}）
     *       放在事务<b>外</b>：磁盘操作可能很慢，不该占着数据库连接；</li>
     *   <li>短事务内只做"插 {@code wb_file} → 插 {@code wb_requirement_node_input} → 回填 {@code ref_id}"；</li>
     *   <li>事务失败时删掉刚落的文件（{@link #deleteFileQuietly}），避免孤儿文件。</li>
     * </ol>
     * <b>为什么必须知道 fileId 才能落盘</b>：文件名前缀就是 {@code fileId}（docs/database.md §5.5），
     * 而 fileId 要插入 {@code wb_file} 才有。因此先插一行"占位文件"（路径列写最终路径）拿到 ID、
     * 落盘成功后再在同一事务里把它补成正式记录？
     * ——不这么做：那样会出现"事务未提交但磁盘已有文件"的窗口，且占位行会被别的事务看到。
     * 这里的做法是<b>先查一次自增值不可行</b>（MySQL 5.7 无 sequence），所以改为：
     * 文件名前缀用"临时 UUID"，插入 {@code wb_file} 拿到 ID 后在同一事务内 rename 成
     * {@code <fileId>_<原名>}，路径列写最终路径；rename 放在事务提交<b>之前</b>的 finally 里失败即回滚。
     * 实际上 {@link #uploadInternal} 把整个流程包在一次事务里，磁盘与数据库同生共死。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @param inputItemId   固定输入项 ID（fixed 必填；free 必须为空）
     * @param originalName  原始文件名
     * @param in            文件流
     * @return 上传结果
     * @throws BizException 404/10301；非成员 403/10201；非创建者非 owner 403/10202；
     *                      节点非当前 / 需求已完成 409/10404；参数或类型不对 400/10001 / 415/10502；
     *                      超出大小 413/10501
     */
    public NodeInputUploadVO uploadInput(Long requirementId, Long nodeId, Long inputItemId,
                                         String originalName, InputStream in) {
        NodeAccess access = requireOperableCurrentNode(requirementId, nodeId);
        Long userId = currentUserContext.require().userId();
        return uploadInternal(access, inputItemId, originalName, in, userId);
    }

    /**
     * 删除已上传的输入（权限 C，且节点为当前节点）。
     * <p>
     * 数据库记录与文件登记一起删（否则 {@code wb_file} 会留下孤儿行），磁盘文件在事务提交后再删：
     * 反过来的顺序一旦事务回滚，就会出现"库里有记录、磁盘没文件"的坏状态。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @param inputRecordId 输入记录 ID
     * @throws BizException 404/10301；403/10201；403/10202；非当前节点 409/10404
     */
    public void deleteInput(Long requirementId, Long nodeId, Long inputRecordId) {
        NodeAccess access = requireOperableCurrentNode(requirementId, nodeId);
        NodeInputFile input = inputRecordId == null ? null : nodeInputMapper.selectById(inputRecordId);
        if (input == null || !Objects.equals(input.getNodeInstanceId(), access.node().getId())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "输入文件不存在");
        }
        FileRecord file = fileMapper.selectById(input.getFileId());
        transactionTemplate.executeWithoutResult(status -> {
            if (nodeInputMapper.deleteById(input.getId(), access.node().getId(), input.getFileId()) != 1) {
                // 并发删除：别人先删掉了，按"已不存在"处理（事务内抛出会回滚，这里没有其它写入）
                throw BizException.of(ErrorCode.NOT_FOUND, "输入文件不存在");
            }
            if (file != null) {
                fileMapper.deleteById(file.getId());
            }
        });
        if (file != null) {
            deleteFileQuietly(file.getStorageRelPath());
        }
        log.info("删除节点输入：requirementId={}, nodeId={}, inputRecordId={}, fileId={}",
                requirementId, nodeId, inputRecordId, input.getFileId());
    }

    /**
     * 上传主体。
     * <p>
     * <b>事务边界（与 {@code SkillService} 同一套思路，但文件命名要求"必须知道 fileId"）</b>：
     * <ol>
     *   <li>先在<b>短事务内</b>插入 {@code wb_file} 与 {@code wb_requirement_node_input}，
     *       拿到 fileId / inputRecordId 并回填 {@code ref_id}；</li>
     *   <li>事务提交后再落盘：{@code inputs/<seq>/<fileId>_<原名>}（临时文件 + 原子 rename）；</li>
     *   <li>落盘成功 → 在一个只更新一列的短事务里回填 {@code storage_rel_path}；</li>
     *   <li>落盘失败 → 删除刚插入的两行（补偿事务）并抛出原来的异常，页面上不会出现"登记了但没文件"。</li>
     * </ol>
     * 反过来的顺序（先落盘再登记）需要先把文件名里的 fileId 留空、提交后再 rename，
     * 中间会有一段"文件已在正式位置、数据库还没说它属于谁"的窗口；当前顺序的窗口只有"文件还没写"，
     * 而读取方（下载 / 预览）发现的只会是 404，不会读到别人的内容。
     *
     * @param access       已校验的节点访问
     * @param inputItemId  固定输入项 ID，可空
     * @param originalName 原始文件名
     * @param in           文件流
     * @param userId       上传人
     * @return 上传结果
     */
    private NodeInputUploadVO uploadInternal(NodeAccess access, Long inputItemId, String originalName,
                                             InputStream in, Long userId) {
        RequirementNode node = access.node();
        NodeSnapshots.NodeSnapshot snapshot = resolveSnapshot(node);
        Long itemId = resolveInputItemId(node, snapshot, inputItemId);
        String safeName = sanitizeFileName(originalName);
        String fileKind = findInputKind(snapshot, itemId);
        if (!NodeStoragePaths.extensionAllowed(fileKind, safeName, true)) {
            throw BizException.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "文件类型不允许（该输入项要求 " + fileKind + "）：" + safeName);
        }

        Path workspace = nodeStoragePaths.workspaceDir(access.requirement().getDshWorkspacePath());
        Path inputDir = nodeStoragePaths.inputDir(workspace, node.getSeq());
        storage.createDirWithin(inputDir);
        storage.requireNoSymlink(inputDir);

        // ① 短事务：先登记（占位路径），拿到 fileId 后才能命名文件
        LocalDateTime now = now();
        UploadHandle handle = transactionTemplate.execute(status -> {
            FileRecord record = new FileRecord();
            record.setProjectId(access.requirement().getProjectId());
            record.setRequirementId(access.requirement().getId());
            record.setRefType(FileRecord.REF_TYPE_NODE_INPUT);
            record.setOriginalName(safeName);
            record.setStorageRelPath("");
            record.setMimeType(probeContentType(safeName));
            // 真实大小与摘要落盘后再回填；先写 0 是为了让 NOT NULL 列有合法的初值
            record.setSizeBytes(0L);
            record.setSha256(ZERO_SHA256);
            record.setUploadedBy(userId);
            record.setCreatedAt(now);
            fileMapper.insert(record);

            NodeInputFile input = new NodeInputFile();
            input.setNodeInstanceId(node.getId());
            input.setInputItemId(itemId);
            input.setFileId(record.getId());
            input.setUploadedBy(userId);
            input.setCreatedAt(now);
            nodeInputMapper.insert(input);
            fileMapper.updateRef(record.getId(), FileRecord.REF_TYPE_NODE_INPUT, input.getId());
            return new UploadHandle(record.getId(), input.getId());
        });

        // ② 落盘（事务外）：临时文件 + 原子 rename，边写边算 sha256
        Path finalFile = nodeStoragePaths.inputFilePath(workspace, node.getSeq(), handle.fileId(), safeName);
        Path tempFile = inputDir.resolve(".uploading-" + UUID.randomUUID());
        FileDigestService.WriteResult written;
        try {
            written = fileDigestService.writeAtomically(in, tempFile, properties.getMaxUploadBytes());
            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (RuntimeException | IOException ex) {
            deleteQuietly(tempFile);
            deleteQuietly(finalFile);
            // ③ 补偿：磁盘失败就把刚登记的记录撤掉，避免"页面有文件、点下载 404"
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    nodeInputMapper.deleteById(handle.inputRecordId(), node.getId(), handle.fileId());
                    fileMapper.deleteById(handle.fileId());
                });
            } catch (RuntimeException cleanupEx) {
                log.error("上传失败后清理登记记录也失败：fileId={}", handle.fileId(), cleanupEx);
            }
            if (ex instanceof BizException biz) {
                throw biz;
            }
            log.error("上传节点输入落盘失败：requirementId={}, nodeId={}",
                    access.requirement().getId(), node.getId(), ex);
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件存储失败");
        }

        // ④ 回填真实路径 / 大小 / 摘要（单条 UPDATE，天然原子）
        String relativePath = storage.relativize(finalFile);
        fileMapper.updateStoredFile(handle.fileId(), relativePath, written.bytes(), written.sha256());
        log.info("上传节点输入：requirementId={}, nodeId={}, fileId={}, inputRecordId={}, itemId={}, bytes={}",
                access.requirement().getId(), node.getId(), handle.fileId(), handle.inputRecordId(), itemId,
                written.bytes());
        return new NodeInputUploadVO(handle.fileId(), safeName, written.bytes(), handle.inputRecordId(), itemId,
                NodeInputUploadVO.toOffset(now));
    }

    /**
     * 解析上传归属的输入项 ID，并按 {@code input_type} 校验（api.md §8 POST 的校验项）。
     *
     * @param node        节点实例
     * @param snapshot    快照
     * @param inputItemId 请求里的输入项 ID
     * @return 输入项 ID（free 节点返回 null）
     * @throws BizException fixed 缺 inputItemId / 不属于快照 400/10001；free 不应带 400/10001
     */
    private Long resolveInputItemId(RequirementNode node, NodeSnapshots.NodeSnapshot snapshot, Long inputItemId) {
        if (WorkflowNode.INPUT_TYPE_FREE.equals(node.getInputType())) {
            if (inputItemId != null) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "该节点是自由输入，上传时不要指定 inputItemId");
            }
            return null;
        }
        if (inputItemId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "固定输入节点上传必须指定 inputItemId");
        }
        boolean belongs = snapshot.inputs().stream()
                .anyMatch(item -> Objects.equals(item.itemId(), inputItemId));
        if (!belongs) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "inputItemId 不属于该节点：" + inputItemId);
        }
        return inputItemId;
    }

    /** 取该输入项要求的 file_kind（找不到按 any 处理，宽容旧数据） */
    private static String findInputKind(NodeSnapshots.NodeSnapshot snapshot, Long itemId) {
        if (itemId == null) {
            return "any";
        }
        return snapshot.inputs().stream()
                .filter(item -> Objects.equals(item.itemId(), itemId))
                .map(NodeSnapshots.InputItem::fileKind)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("any");
    }

    // ------------------------------------------------------------------
    // 内部接口：节点上下文 / 引用登记
    // ------------------------------------------------------------------

    /**
     * 节点上下文（{@code GET /internal/nodes/{nid}/context}，供插件 {@code node_context_get}）。
     * <p>
     * 不做"当前节点"限制：模型可能在查看非当前节点时被调用，此时 {@code readonly=true}，
     * 提交会由推进服务拒绝；这里如实给出状态，让模型自己判断"该不该在这里干活"。
     *
     * @param nodeInstanceId  节点实例 ID
     * @param operatorCommand 调用方标识（可空，默认 agent）
     * @return 上下文
     * @throws BizException 节点不存在 404/10301
     */
    public NodeContextVO context(Long nodeInstanceId, String operatorCommand) {
        InternalOperator operator = resolveInternalOperator(nodeInstanceId, operatorCommand);
        RequirementWithStats requirement = requirementMapper.selectById(operator.requirementId());
        RequirementNode node = requirementMapper.selectNodeById(nodeInstanceId);
        NodeSnapshots.NodeSnapshot snapshot = resolveSnapshot(node);
        List<RequirementNode> nodes = requirementMapper.selectNodesByRequirement(requirement.getId());

        List<NodeContextVO.SkillRef> skills = snapshot.skills().stream()
                .map(item -> new NodeContextVO.SkillRef(item.name(), item.versionNo()))
                .toList();

        List<NodeInputFile> inputFiles = nodeInputMapper.selectByNode(node.getId());
        Path workspace = nodeStoragePaths.workspaceDir(requirement.getDshWorkspacePath());
        Map<Long, List<FileRecord>> filesById = loadFiles(inputFiles.stream().map(NodeInputFile::getFileId).toList());

        List<NodeContextVO.InputFileRef> inputItems = new ArrayList<>();
        List<NodeContextVO.FileRef> freeFiles = new ArrayList<>();
        if (WorkflowNode.INPUT_TYPE_FREE.equals(node.getInputType())) {
            inputFiles.stream()
                    .filter(file -> file.getInputItemId() == null)
                    .forEach(file -> freeFiles.add(toFileRef(file, workspace, filesById)));
        } else {
            for (NodeSnapshots.InputItem item : snapshot.inputs()) {
                List<NodeContextVO.FileRef> itemFiles = inputFiles.stream()
                        .filter(file -> Objects.equals(file.getInputItemId(), item.itemId()))
                        .map(file -> toFileRef(file, workspace, filesById))
                        .toList();
                inputItems.add(new NodeContextVO.InputFileRef(item.itemId(), item.name(), item.fileKind(),
                        Boolean.TRUE.equals(item.required()), itemFiles));
            }
        }

        Map<String, NodeOutputRecord> records = new LinkedHashMap<>();
        for (NodeOutputRecord record : nodeOutputMapper.selectByNode(node.getId())) {
            records.put(record.getItemKey(), record);
        }
        Map<Long, FileRecord> outputFiles = loadFiles(records.values().stream().map(NodeOutputRecord::getFileId).toList())
                .entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get(0)));
        String outputDir = NodeStoragePaths.modelOutputDirRelative(node.getId());
        List<NodeContextVO.OutputItemRef> outputs = snapshot.outputs().stream().map(item -> {
            NodeOutputRecord record = records.get(item.itemKey());
            FileRecord file = record == null ? null : outputFiles.get(record.getFileId());
            String relative = file == null ? null
                    : relativeOf(workspace, file.getStorageRelPath());
            return new NodeContextVO.OutputItemRef(item.itemKey(), item.name(), item.fileKind(),
                    Boolean.TRUE.equals(item.required()), record != null,
                    record == null ? null : record.getVersionNo(),
                    file == null ? null : file.getOriginalName(),
                    relative,
                    outputDir + item.itemKey() + "." + defaultExtension(item.fileKind()));
        }).toList();

        NodeContextVO.PreviousNode previous = buildContextPreviousNode(requirement, node, workspace);
        String nextNodeName = nodes.stream()
                .filter(candidate -> Objects.equals(candidate.getSeq(), node.getSeq() + 1))
                .map(RequirementNode::getName)
                .findFirst()
                .orElse(null);
        boolean readonly = isReadonly(requirement, node, operator.userId());

        return new NodeContextVO(node.getId(), requirement.getId(), requirement.getTitle(),
                requirement.getStatus(), requirement.getDshWorkspacePath(), node.getSeq(), nodes.size(),
                node.getName(), node.getDescription(), node.getStatus(), node.getInputType(), readonly,
                skills, inputItems, freeFiles, outputs, previous, outputDir, nextNodeName);
    }

    /**
     * 记录"模型读取了某个引用文件"（{@code POST /internal/nodes/{nid}/reference}）。
     * <p>
     * 校验的是"这个文件确实属于该节点的上下文"：要么是该节点的输入文件，要么是该节点 / 上一节点的
     * 已提交输出。不满足即 400——避免插件把任意 fileId 记成"读过本节点资料"。
     * 通过后只写日志（理由见 {@link NodeReferenceCommand} 的注释）。
     *
     * @param nodeInstanceId  节点实例 ID
     * @param command         引用命令
     * @param operatorCommand 调用方标识（可空）
     * @return 记录结果（供插件回显"已登记"）
     * @throws BizException 节点 404/10301；文件不属于上下文 / 缺失 fileId 400/10001
     */
    public ReferenceResult registerReference(Long nodeInstanceId, NodeReferenceCommand command,
                                             String operatorCommand) {
        InternalOperator operator = resolveInternalOperator(nodeInstanceId, operatorCommand);
        if (command == null || command.fileId() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "缺少 fileId");
        }
        Long fileId = command.fileId();
        RequirementNode node = requirementMapper.selectNodeById(nodeInstanceId);
        List<RequirementNode> nodes = requirementMapper.selectNodesByRequirement(operator.requirementId());

        boolean allowed = nodeInputMapper.selectByNode(node.getId()).stream()
                .anyMatch(input -> Objects.equals(input.getFileId(), fileId));
        if (!allowed) {
            List<RequirementNode> comparable = nodes.stream()
                    .filter(candidate -> candidate.getSeq() != null
                            && (candidate.getId().equals(node.getId())
                            || candidate.getSeq() == node.getSeq() - 1))
                    .toList();
            for (RequirementNode candidate : comparable) {
                if (nodeOutputMapper.selectByNode(candidate.getId()).stream()
                        .anyMatch(record -> Objects.equals(record.getFileId(), fileId))) {
                    allowed = true;
                    break;
                }
            }
        }
        if (!allowed) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件不属于该节点的上下文：" + fileId);
        }
        FileRecord file = fileMapper.selectById(fileId);
        log.info("node-reference: requirementId={}, nodeId={}, fileId={}, fileName={}, kind={}, itemKey={},"
                        + " operator={}",
                operator.requirementId(), nodeInstanceId, fileId,
                file == null ? null : file.getOriginalName(),
                command.kind() == null ? "input" : command.kind(), command.itemKey(), operator.operator());
        return new ReferenceResult(fileId, file == null ? null : file.getOriginalName(),
                command.kind() == null ? "input" : command.kind(), true);
    }

    // ------------------------------------------------------------------
    // 供推进服务复用（包内）
    // ------------------------------------------------------------------

    /**
     * 解析内部接口的操作人：节点 → 需求 → 创建人（{@link InternalOperator}）。
     *
     * @param nodeInstanceId  节点实例 ID
     * @param operatorCommand 调用方标识，可空
     * @return 操作人
     * @throws BizException 节点不存在 404/10301
     */
    InternalOperator resolveInternalOperator(Long nodeInstanceId, String operatorCommand) {
        RequirementNode node = nodeInstanceId == null ? null : requirementMapper.selectNodeById(nodeInstanceId);
        if (node == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "节点实例不存在：" + nodeInstanceId);
        }
        Requirement requirement = requirementMapper.selectById(node.getRequirementId());
        if (requirement == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "需求不存在");
        }
        String operator = operatorCommand == null || operatorCommand.isBlank() ? "agent" : operatorCommand.trim();
        return new InternalOperator(requirement.getCreatorId(), operator, node.getId(), requirement.getId(),
                requirement.getProjectId(), requirement.getTitle());
    }

    // ------------------------------------------------------------------
    // 供推进服务复用（包内可见）：推进服务不重复注入同一批依赖，避免两处各自组装
    // ------------------------------------------------------------------

    RequirementMapper requirementMapper() {
        return requirementMapper;
    }

    FileMapper fileMapper() {
        return fileMapper;
    }

    NodeOutputMapper nodeOutputMapper() {
        return nodeOutputMapper;
    }

    TransactionTemplate transactionTemplate() {
        return transactionTemplate;
    }

    WorkbenchStorage storage() {
        return storage;
    }

    NodeStoragePaths storagePaths() {
        return nodeStoragePaths;
    }

    FileDigestService digestService() {
        return fileDigestService;
    }

    WorkbenchStorageProperties properties() {
        return properties;
    }

    /**
     * 解析节点快照（三个 JSON 列）。
     *
     * @param node 节点实例
     * @return 快照（三个列表都不为 null）
     * @throws BizException JSON 损坏 → 500/19901（数据异常，不能静默当成空快照）
     */
    NodeSnapshots.NodeSnapshot resolveSnapshot(RequirementNode node) {
        try {
            List<NodeSnapshots.InputItem> inputs = readList(node.getInputsSnapshot(), INPUT_ITEMS);
            List<NodeSnapshots.OutputItem> outputs = readList(node.getOutputsSnapshot(), OUTPUT_ITEMS);
            List<NodeSnapshots.SkillItem> skills = readList(node.getSkillsSnapshot(), SKILL_ITEMS);
            return new NodeSnapshots.NodeSnapshot(inputs, outputs, skills);
        } catch (IOException ex) {
            log.error("节点快照解析失败：nodeId={}", node.getId(), ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "节点快照数据异常：" + node.getId());
        }
    }

    /**
     * 只读判定（api.md §8 三种情况，见类注释）。
     *
     * @param requirement 需求
     * @param node        节点
     * @param userId      当前登录人
     * @return true 表示只读
     */
    boolean isReadonly(Requirement requirement, RequirementNode node, Long userId) {
        if (!RequirementNode.STATUS_CURRENT.equals(node.getStatus())) {
            return true;
        }
        if (!Requirement.STATUS_IN_PROGRESS.equals(requirement.getStatus())) {
            return true;
        }
        if (!Objects.equals(requirement.getCreatorId(), userId)) {
            ProjectWithStats project = projectAccessService.requireExistingProject(requirement.getProjectId());
            return !projectAccessService.isOwner(project, userId);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 访问校验
    // ------------------------------------------------------------------

    /**
     * 读取类访问：需求存在 → 项目存在 → 是成员 → 节点属于该需求，并算出只读。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @return 访问上下文
     * @throws BizException 404/10301；非成员 403/10201
     */
    NodeAccess requireMemberAccess(Long requirementId, Long nodeId) {
        CurrentUser user = currentUserContext.require();
        RequirementWithStats requirement = requireExistingRequirement(requirementId);
        projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());
        RequirementNode node = requireNodeOfRequirement(requirement, nodeId);
        return new NodeAccess(requirement, node, isReadonly(requirement, node, user.userId()));
    }

    /**
     * 写类访问（权限 C）：读取类校验 + 需求创建人或项目 owner。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @return 访问上下文
     * @throws BizException 404/10301；403/10201；非创建者非 owner 403/10202
     */
    NodeAccess requireOperableAccess(Long requirementId, Long nodeId) {
        CurrentUser user = currentUserContext.require();
        RequirementWithStats requirement = requireExistingRequirement(requirementId);
        ProjectWithStats project = projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());
        boolean owner = projectAccessService.isOwner(project, user.userId());
        if (!owner && !Objects.equals(requirement.getCreatorId(), user.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有需求创建者或项目 owner 可以操作该节点");
        }
        RequirementNode node = requireNodeOfRequirement(requirement, nodeId);
        return new NodeAccess(requirement, node, isReadonly(requirement, node, user.userId()));
    }

    /**
     * 写类访问 + "必须是当前节点且需求进行中"（上传 / 删除输入的前提）。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @return 访问上下文
     * @throws BizException 404/10301；403/10201；403/10202；非当前节点 / 需求已完成 409/10404
     */
    private NodeAccess requireOperableCurrentNode(Long requirementId, Long nodeId) {
        NodeAccess access = requireOperableAccess(requirementId, nodeId);
        if (!Requirement.STATUS_IN_PROGRESS.equals(access.requirement().getStatus())) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "需求已完成，不能再修改节点输入");
        }
        if (!RequirementNode.STATUS_CURRENT.equals(access.node().getStatus())) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "只有当前节点可以上传或删除输入");
        }
        return access;
    }

    private RequirementWithStats requireExistingRequirement(Long requirementId) {
        RequirementWithStats requirement =
                requirementId == null ? null : requirementMapper.selectById(requirementId);
        if (requirement == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "需求不存在");
        }
        return requirement;
    }

    private RequirementNode requireNodeOfRequirement(RequirementWithStats requirement, Long nodeId) {
        RequirementNode node = nodeId == null ? null : requirementMapper.selectNodeById(nodeId);
        if (node == null || !Objects.equals(node.getRequirementId(), requirement.getId())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    // ------------------------------------------------------------------
    // 上下文辅助
    // ------------------------------------------------------------------

    /** 上下文里的上一节点（与节点信息页同口径：{@code seq-1} 已提交的输出） */
    private NodeContextVO.PreviousNode buildContextPreviousNode(RequirementWithStats requirement,
                                                                RequirementNode node, Path workspace) {
        if (node.getSeq() == null || node.getSeq() <= 1) {
            return null;
        }
        RequirementNode previous = requirementMapper.selectNodesByRequirement(requirement.getId()).stream()
                .filter(candidate -> Objects.equals(candidate.getSeq(), node.getSeq() - 1))
                .findFirst()
                .orElse(null);
        if (previous == null) {
            return null;
        }
        List<NodeOutputRecord> records = nodeOutputMapper.selectByNode(previous.getId());
        Map<Long, List<FileRecord>> files = loadFiles(records.stream().map(NodeOutputRecord::getFileId).toList());
        List<NodeContextVO.PreviousOutput> outputs = records.stream()
                .sorted(Comparator.comparing(NodeOutputRecord::getItemKey))
                .map(record -> {
                    List<FileRecord> matched = files.get(record.getFileId());
                    FileRecord file = matched == null || matched.isEmpty() ? null : matched.get(0);
                    return new NodeContextVO.PreviousOutput(record.getItemKey(), record.getItemKey(),
                            file == null ? null : relativeOf(workspace, file.getStorageRelPath()),
                            record.getFileId());
                })
                .toList();
        return new NodeContextVO.PreviousNode(previous.getSeq(), previous.getName(), outputs);
    }

    private NodeContextVO.FileRef toFileRef(NodeInputFile file, Path workspace,
                                            Map<Long, List<FileRecord>> filesById) {
        List<FileRecord> matched = filesById.get(file.getFileId());
        FileRecord record = matched == null || matched.isEmpty() ? null : matched.get(0);
        return new NodeContextVO.FileRef(file.getFileId(), file.getOriginalName(),
                record == null ? null : relativeOf(workspace, record.getStorageRelPath()),
                file.getSizeBytes(), file.getSha256());
    }

    /**
     * 批量取文件登记（上下文里一次取全，避免逐个回查）。
     *
     * @param fileIds 文件 ID（可含 null）
     * @return fileId → 文件列表（保留查询顺序）
     */
    private Map<Long, List<FileRecord>> loadFiles(List<Long> fileIds) {
        Map<Long, List<FileRecord>> result = new LinkedHashMap<>();
        fileIds.stream().filter(Objects::nonNull).distinct().forEach(id -> {
            FileRecord file = fileMapper.selectById(id);
            if (file != null) {
                result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(file);
            }
        });
        return result;
    }

    /** 库中相对存储根的路径 → 相对 workspace 的路径（模型可读） */
    private String relativeOf(Path workspace, String storageRelPath) {
        if (storageRelPath == null || storageRelPath.isBlank()) {
            return null;
        }
        Path file = storage.resolveWithin(storage.root(), storageRelPath);
        return NodeStoragePaths.relativeToWorkspace(workspace, file);
    }

    /** 模型输出建议扩展名：按 file_kind 给最常用的一种 */
    static String defaultExtension(String fileKind) {
        return "document".equals(fileKind) ? "docx" : "md";
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> readList(String json, TypeReference<List<T>> type) throws IOException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<T> value = snapshotMapper.readValue(json, type);
        return value == null ? List.of() : value;
    }

    // ------------------------------------------------------------------
    // 文件名 / 磁盘小工具
    // ------------------------------------------------------------------

    /**
     * 净化上传文件名：只留文件名部分（去路径），去掉控制字符，长度上限内截断。
     *
     * @param originalName 原始文件名
     * @return 安全文件名
     * @throws BizException 空文件名 → 400/10001
     */
    static String sanitizeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }
        String name = originalName.trim();
        // 客户端可能带完整路径（Windows 用 \，Unix 用 /），只取最后一段
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        // 控制字符与路径分隔符都不允许出现在落盘名里
        name = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (name.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件名不能为空");
        }
        if (name.length() > MAX_SAFE_FILE_NAME_LENGTH) {
            String extension = NodeStoragePaths.extensionOf(name);
            String stem = name.substring(0, MAX_SAFE_FILE_NAME_LENGTH - (extension.isEmpty() ? 0 : extension.length() + 1));
            name = extension.isEmpty() ? stem : stem + "." + extension;
        }
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    /** 探测 MIME（失败返回 null，不阻断上传） */
    private static String probeContentType(String fileName) {
        try {
            String probed = Files.probeContentType(Path.of(fileName));
            return probed == null ? null : (probed.length() > 128 ? probed.substring(0, 128) : probed);
        } catch (IOException ex) {
            return null;
        }
    }

    /** 同目录原子移动（临时文件 → 正式名） */
    private static void moveAtomically(Path source, Path target) {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "文件写入失败：" + target.getFileName());
        }
    }

    /** 按库中相对路径删磁盘文件（提交后清理用；失败只记日志） */
    void deleteFileQuietly(String storageRelPath) {
        if (storageRelPath == null || storageRelPath.isBlank()) {
            return;
        }
        try {
            Path path = storage.resolveWithin(storage.root(), storageRelPath);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | BizException ex) {
            log.warn("清理文件失败（可忽略，按留存策略保留）：{}", storageRelPath);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    /** 当前业务时间（毫秒精度，与 DATETIME(3) 一致） */
    static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 一次请求内的节点访问上下文。
     *
     * @param requirement 需求
     * @param node        节点实例
     * @param readonly    该节点对当前用户是否只读
     */
    record NodeAccess(RequirementWithStats requirement, RequirementNode node, boolean readonly) {
    }

    /**
     * 上传第一步的结果：文件行与输入记录的 ID（落盘要按 fileId 命名，回填要按 inputRecordId 定位）。
     *
     * @param fileId        文件 ID
     * @param inputRecordId 输入记录 ID
     */
    private record UploadHandle(Long fileId, Long inputRecordId) {
    }

    /**
     * 引用登记结果。
     *
     * @param fileId    文件 ID
     * @param fileName  文件名
     * @param kind      引用类型
     * @param recorded  是否已记录（恒为 true，走到这里就是成功）
     */
    public record ReferenceResult(Long fileId, String fileName, String kind, Boolean recorded) {
    }
}
