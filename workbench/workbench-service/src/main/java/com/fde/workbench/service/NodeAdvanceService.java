package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.FileRecord;
import com.fde.workbench.dao.entity.NodeOutputRecord;
import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.entity.RequirementNode;
import com.fde.workbench.dao.entity.RequirementWithStats;
import com.fde.workbench.service.vo.OutputCommitVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 登记模型产出的独立文件副本。需求行锁保证读取当前版本、登记新版本与推进使用同一事务视图。
 * 已完成节点仍可重提交，已完成需求拒绝提交。
 */
@Service
public class NodeAdvanceService {

    private static final Logger log = LoggerFactory.getLogger(NodeAdvanceService.class);

    private final NodeWorkflowService nodeWorkflowService;

    private final NodeAdvanceEvaluator advanceEvaluator;

    public NodeAdvanceService(NodeWorkflowService nodeWorkflowService,
                              NodeAdvanceEvaluator advanceEvaluator) {
        this.nodeWorkflowService = nodeWorkflowService;
        this.advanceEvaluator = advanceEvaluator;
    }

    /**
     * 登记一次模型产出，满足条件时推进节点（docs/api.md §11）。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param itemKey        输出项稳定键
     * @param relativePath   模型写入的文件路径（<b>相对需求 workspace 根</b>，如
     *                       {@code .workbench/101/requirement-items.md}；也接受同样以
     *                       {@code .workbench/} 开头的绝对路径，见 {@link #normalizeModelPath}）
     * @param sha256         文件内容摘要（插件已算好；后端再算一次比对）
     * @param operator       调用方标识（agent / user），只用于记录
     * @return 提交结果
     * @throws BizException 节点不存在 404/10301；itemKey 不属于节点 400/10001；
     *                      路径越界 / 文件缺失 / 摘要不符 400/10001；超出大小 413/10501；
     *                      需求已完成 409/10404
     */
    public OutputCommitVO commitOutput(Long nodeInstanceId, String itemKey,
                                                                     String relativePath, String sha256,
                                                                     String operator) {
        InternalCommitContext context = requireCommitContext(nodeInstanceId, itemKey, operator);
        RequirementNode node = context.node();
        RequirementWithStats requirement = context.requirement();
        if (!Requirement.STATUS_IN_PROGRESS.equals(requirement.getStatus())) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "需求已完成，不能再提交产出");
        }

        // ① 路径：相对 workspace 根 → 节点输出目录；resolveWithin 拒绝 ".."、绝对路径、盘符、反斜杠
        Path workspace = nodeWorkflowService.storagePaths()
                .workspaceDir(requirement.getDshWorkspacePath());
        Path outputDir = nodeWorkflowService.storagePaths().modelOutputDir(workspace, node.getId());
        String normalizedPath = normalizeModelPath(relativePath, workspace);
        Path source = requireModelFile(workspace, outputDir, normalizedPath);

        String expected = FileDigestService.normalizeSha256(sha256, "sha256 必须是 64 位十六进制字符串");
        String sourceExtension = NodeStoragePaths.extensionOf(source.getFileName().toString());
        String extension = sourceExtension.isEmpty()
                ? NodeWorkflowService.defaultExtension(context.outputItem().fileKind()) : sourceExtension;
        Path copyDir = nodeWorkflowService.storagePaths().outputCopyDir(workspace, node.getSeq());
        nodeWorkflowService.storage().createDirWithin(copyDir);
        Path staged = copyDir.resolve(".uploading-" + UUID.randomUUID());
        try {
            // 在事务外把模型文件复制成独立样本，并校验实际将要登记的内容。
            FileDigestService.WriteResult copied = nodeWorkflowService.digestService().copyAtomically(
                    source, staged, nodeWorkflowService.properties().getNodeOutputMaxBytes());
            if (!copied.sha256().equals(expected)) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "文件 sha256 与声明不一致，请重新写入后再提交");
            }
            OutputCommitVO outcome = nodeWorkflowService.transactionTemplate().execute(status -> {
                Requirement locked = nodeWorkflowService.lockRequirement(requirement.getId());
                if (!Requirement.STATUS_IN_PROGRESS.equals(locked.getStatus())) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "需求已完成，不能再提交产出");
                }
                RequirementNode currentNode = nodeWorkflowService.requireNodeOfRequirement(locked, node.getId());
                NodeOutputRecord current = nodeWorkflowService.nodeOutputMapper().selectByNodeAndKey(node.getId(),
                        itemKey);
                LocalDateTime now = NodeWorkflowService.now();
                if (current != null && isSameContent(current, expected)) {
                    var advance = advanceEvaluator.evaluateAndAdvance(locked, currentNode, context.snapshot(), now);
                    return toCommitResult(current.getVersionNo(), advance);
                }

                int versionNo = current == null ? 1 : current.getVersionNo() + 1;
                Path target = nodeWorkflowService.storagePaths().outputCopyPath(workspace, node.getSeq(), itemKey,
                        versionNo, extension);
                FileRecord file = new FileRecord();
                file.setProjectId(locked.getProjectId());
                file.setRequirementId(locked.getId());
                file.setRefType(FileRecord.REF_TYPE_NODE_OUTPUT);
                // 登记名取模型写入时的文件名，不拼 itemKey：用户下载到的必须是"服务概述.md"这类
                // 能认出对应哪一项交付的名字，而 itemKey 只是内部稳定键（如 output-2）。
                // 磁盘副本仍按 <itemKey>.v<N>.<ext> 存放，避免同一输出项的多个版本互相覆盖。
                file.setOriginalName(outputFileName(source, context.outputItem(), extension));
                file.setStorageRelPath(nodeWorkflowService.storage().relativize(target));
                file.setSizeBytes(copied.bytes());
                file.setSha256(copied.sha256());
                file.setUploadedBy(context.operator().userId());
                file.setCreatedAt(now);
                nodeWorkflowService.fileMapper().insert(file);

                nodeWorkflowService.nodeOutputMapper().upsertCommit(node.getId(), context.outputItem().itemId(),
                        itemKey, file.getId(), context.operator().userId(), now);
                // 回读登记行，核对版本，避免文件名与库中版本不匹配。
                NodeOutputRecord saved = nodeWorkflowService.nodeOutputMapper().selectByNodeAndKey(node.getId(), itemKey);
                if (saved == null || saved.getVersionNo() != versionNo || !Objects.equals(saved.getFileId(), file.getId())
                        || nodeWorkflowService.fileMapper().updateRef(file.getId(), FileRecord.REF_TYPE_NODE_OUTPUT,
                        saved.getId()) != 1) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR, "输出项登记失败：" + itemKey);
                }
                // 正式副本发布后再判定，文件登记与推进在同一个事务提交。
                nodeWorkflowService.storagePaths().publishStagedFile(staged, target);
                var advance = advanceEvaluator.evaluateAndAdvance(locked, currentNode, context.snapshot(), now);
                return toCommitResult(versionNo, advance);
            });
            log.info("提交节点输出：requirementId={}, nodeId={}, itemKey={}, versionNo={}, advanced={}, operator={}",
                    requirement.getId(), node.getId(), itemKey, outcome.versionNo(), outcome.advanced(),
                    context.operator().operator());
            return outcome;
        } finally {
            nodeWorkflowService.storagePaths().deleteQuietly(staged);
        }
    }

    /**
     * 输出文件的登记名（用户下载时看到的文件名）。
     * <p>
     * 优先用模型写入时的文件名（`node_output_write` 已按"输出项名称 + 扩展名"生成），
     * 这样下载下来就是「服务概述.md」；只有在拿不到文件名时才退回输出项名称。
     *
     * @param source 模型写入的文件（在 `.workbench/<nid>/` 下）
     * @param item   输出项（用于兜底）
     * @param extension 本次登记的扩展名（源文件没有扩展名时由这里补齐）
     * @return 合法文件名
     */
    private static String outputFileName(Path source,
                                        com.fde.workbench.service.vo.NodeSnapshots.OutputItem item,
                                        String extension) {
        String name = source.getFileName().toString();
        return name.isBlank() ? item.itemKey() + "." + extension : name;
    }

    private boolean isSameContent(NodeOutputRecord current, String sha256) {
        FileRecord file = nodeWorkflowService.fileMapper().selectById(current.getFileId());
        return file != null && sha256.equals(file.getSha256());
    }

    private static OutputCommitVO toCommitResult(int versionNo, NodeAdvanceEvaluator.AdvanceOutcome advance) {
        return new OutputCommitVO(true, versionNo, advance.advanced(), advance.nextNodeName(),
                advance.completed(), advance.remainingOutputs(), advance.remainingInputs());
    }

    // ------------------------------------------------------------------
    // 校验辅助
    // ------------------------------------------------------------------

    /**
     * 定位节点、解析快照、校验 {@code itemKey} 属于该节点输出项。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param itemKey        输出项 key
     * @param operator       调用方标识
     * @return 提交上下文
     * @throws BizException 节点 404/10301；itemKey 不属于节点 400/10001
     */
    private InternalCommitContext requireCommitContext(Long nodeInstanceId, String itemKey, String operator) {
        com.fde.workbench.service.bo.InternalOperator internal =
                nodeWorkflowService.resolveInternalOperator(nodeInstanceId, operator);
        RequirementNode node = nodeWorkflowService.requirementMapper().selectNodeById(nodeInstanceId);
        RequirementWithStats requirement = nodeWorkflowService.requirementMapper()
                .selectById(internal.requirementId());
        if (requirement == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "需求不存在");
        }
        if (itemKey == null || itemKey.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "itemKey 不能为空");
        }
        var snapshot = nodeWorkflowService.resolveSnapshot(node);
        var outputItem = snapshot.outputs().stream()
                .filter(item -> itemKey.equals(item.itemKey()))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.PARAM_INVALID,
                        "itemKey 不属于该节点的输出项：" + itemKey + "（可用："
                                + snapshot.outputs().stream().map(i -> i.itemKey()).toList() + "）"));
        return new InternalCommitContext(node, requirement, internal, snapshot, outputItem);
    }

    /**
     * 规范化模型写入路径，统一成"相对 workspace 根"的写法。
     * <p>
     * 两种形态都接受，但都只能落在 {@code .workbench/} 下：
     * <ul>
     *   <li>相对：{@code .workbench/101/clarify-notes.md}（api.md §11 的契约写法，模型 cwd = workspace 根）；</li>
     *   <li>绝对（且在该 workspace 内）：例如 {@code mkdir -p .workbench/101 && cd .workbench/101 && pwd}
     *       得到的绝对目录，常见于 shell 里 pushd 后写文件的场景。之所以接受它，是因为它经过
     *       "必须等于本需求 workspace + 相对部分"的严格换算，<b>不会放宽越界判定</b>；
     *       其它绝对路径（{@code /etc/passwd}、别的需求的 workspace）一律拒绝。</li>
     * </ul>
     *
     * @param relativePath 请求里的路径
     * @param workspace    需求 workspace
     * @return 相对 workspace 根的路径
     * @throws BizException 缺失 / 非法绝对路径 400/10001
     */
    private static String normalizeModelPath(String relativePath, Path workspace) {
        String value = relativePath == null ? "" : relativePath.trim();
        if (value.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "relativePath 不能为空");
        }
        if (value.startsWith("/") || value.startsWith("~") || value.length() > 1 && value.charAt(1) == ':') {
            Path base = workspace.toAbsolutePath().normalize();
            Path given = null;
            try {
                given = Path.of(value).toAbsolutePath().normalize();
            } catch (RuntimeException ex) {
                // 非法路径串（例如 Windows 风格）→ 按"不是本 workspace 内的绝对路径"处理
                given = null;
            }
            if (given == null || !given.startsWith(base)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "relativePath 必须是相对 workspace 的路径：" + relativePath);
            }
            value = base.relativize(given).toString().replace('\\', '/');
        }
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }

    /**
     * 校验模型文件：必须解析到"本节点输出目录之内"且是普通文件（不是软链）。
     * <p>
     * {@link WorkbenchStorage#resolveWithin} 已经拒绝 {@code ..} / 绝对路径 / 盘符 / 反斜杠，
     * 这里再判一次"仍在 outputDir 内"，双重保险；随后拒绝符号链接——否则把
     * {@code .workbench/<nid>/x.md} 换成指向存储根外的软链就能让后端把任意文件登记成输出。
     *
     * @param workspace 需求 workspace
     * @param outputDir 本节点输出目录（{@code <ws>/.workbench/<nid>}）
     * @param relative  相对 workspace 的路径
     * @return 文件绝对路径
     * @throws BizException 越界 / 不在输出目录 400/10001；文件缺失 400/10001
     */
    private Path requireModelFile(Path workspace, Path outputDir, String relative) {
        Path candidate = nodeWorkflowService.storage().resolveWithin(workspace, relative);
        if (!candidate.startsWith(outputDir)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "relativePath 必须位于本节点输出目录 .workbench/" + outputDir.getFileName() + "/ 内：" + relative);
        }
        if (Files.isSymbolicLink(candidate)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "输出文件不得是符号链接：" + relative);
        }
        if (!Files.isRegularFile(candidate)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "输出文件不存在：" + relative);
        }
        return candidate;
    }

    /**
     * 提交上下文。
     *
     * @param node       节点实例
     * @param requirement 所属需求
     * @param operator   操作人
     * @param snapshot   节点快照
     * @param outputItem 匹配到的输出项
     */
    private record InternalCommitContext(RequirementNode node, RequirementWithStats requirement,
                                         com.fde.workbench.service.bo.InternalOperator operator,
                                         com.fde.workbench.service.vo.NodeSnapshots.NodeSnapshot snapshot,
                                         com.fde.workbench.service.vo.NodeSnapshots.OutputItem outputItem) {
    }

}
