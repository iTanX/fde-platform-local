package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.FileRecord;
import com.fde.workbench.dao.entity.NodeOutputRecord;
import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.entity.RequirementNode;
import com.fde.workbench.dao.entity.RequirementWithStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 模型产出登记与节点推进（docs/api.md §11 commit、docs/database.md §5.3/§5.4、
 * plugin-integration.md §7、PRD AC-F4.6/F6.2/F6.3/H5.2）。
 * <p>
 * <b>这是核心链路，顺序不能改</b>：
 * <ol>
 *   <li><b>校验</b>（全部在事务外，只读）：{@code itemKey} 属于该节点输出项快照 →
 *       {@code relativePath} 位于该节点输出目录 {@code <ws>/.workbench/<nodeInstanceId>/} 内 →
 *       文件存在且是普通文件（非软链）→ 大小不超限 → <b>sha256 与请求一致</b>；</li>
 *   <li><b>复制</b>：把模型写的文件复制到 {@code <ws>/outputs/<seq>/<itemKey>.<ext>}，
 *       以副本的摘要作为 {@code wb_file.sha256}——{@code .workbench/} 里的文件可能被后续对话改写，
 *       而页面上的输出必须稳定可下载（理由见 {@link NodeStoragePaths}）；</li>
 *   <li><b>单事务</b>：插 {@code wb_file}（ref_type=node_output）→ UPSERT
 *       {@code wb_requirement_node_output}（重提交 {@code version_no+1}）→ 重新判定必填项 →
 *       满足才推进；</li>
 *   <li><b>推进</b>：当前节点 {@code done}（写 {@code completed_at}、{@code completed_by}）→
 *       下一节点 {@code current}（写 {@code started_at}）→ {@code requirement.current_seq+1}；
 *       已是最后节点则 {@code requirement.status='completed'}。</li>
 * </ol>
 * <b>"半提交绝不推进"</b>：登记与推进在同一个事务里，判定用 {@link NodeAdvanceEvaluator} 读的是
 * <b>事务内</b>刚写好的输出记录，因此"输出两项只提交了一项"时当前事务里就能看到缺项，直接不推进；
 * 反过来若判定在事务外做，就可能出现"A 提交判定通过 → B 插入新版本 → A 推进"的错乱。
 * <p>
 * <b>为什么不检查"是不是当前节点"</b>：{@code commit} 来自插件，判定口径以<b>必填项齐备</b>为准
 * （docs/database.md §5.3）。对已完成节点重复提交会登记新版本但不再推进（节点已不是 {@code current}，
 * {@code markNodeDone} 影响 0 行），对未开始的节点提交则不会推进（它的必填输入通常还没传）。
 * 这里只拒绝"需求已完成"之后的提交——那时推进已无意义，避免给已完成需求制造脏数据。
 */
@Service
public class NodeAdvanceService {

    private static final Logger log = LoggerFactory.getLogger(NodeAdvanceService.class);

    private static final String OPERATOR_AGENT = "agent";

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
    public com.fde.workbench.service.vo.OutputCommitVO commitOutput(Long nodeInstanceId, String itemKey,
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

        // ② 摘要：先算 sha256 比对（不符即拒绝），保证登记的"内容"就是模型当时写的内容
        String expected = FileDigestService.normalizeSha256(sha256, "sha256 必须是 64 位十六进制字符串");
        String actual = nodeWorkflowService.digestService().sha256Of(source);
        if (!actual.equals(expected)) {
            log.warn("输出摘要不一致：nodeId={}, itemKey={}, path={}, expected={}, actual={}",
                    node.getId(), itemKey, normalizedPath, expected, actual);
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件 sha256 与声明不一致，请重新写入后再提交");
        }
        // 内容变化才产生新版本（docs/database.md §5.4：重新提交 version_no+1）。
        // 同一份内容重复提交（模型重试 / 多轮对话里又提了一次）不再写新版本，但仍<b>重新判定一次推进条件</b>：
        // 常见场景是"先提交了输出、后补了必填输入"，此时重提同一内容正是触发推进的时机。
        NodeOutputRecord current = nodeWorkflowService.nodeOutputMapper()
                .selectByNodeAndKey(node.getId(), itemKey);
        if (current != null && isSameContent(current, actual)) {
            log.info("输出内容未变化，跳过新版本：nodeId={}, itemKey={}, versionNo={}", node.getId(), itemKey,
                    current.getVersionNo());
            return commitUnchanged(context, current);
        }

        long size = sizeOf(source);
        long maxBytes = nodeWorkflowService.properties().getNodeOutputMaxBytes();
        if (maxBytes > 0 && size > maxBytes) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                    "输出文件超出大小限制（上限 " + maxBytes + " 字节）：" + size);
        }

        // ③ 复制到稳定位置（事务外）：副本摘要作为 wb_file.sha256，模型后续改写 .workbench 不影响它
        String extension = NodeStoragePaths.extensionOf(normalizedPath);
        Path copyDir = nodeWorkflowService.storagePaths().outputCopyDir(workspace, node.getSeq());
        nodeWorkflowService.storage().createDirWithin(copyDir);
        Path copy = nodeWorkflowService.storagePaths()
                .outputCopyPath(workspace, node.getSeq(), itemKey, extension);
        FileDigestService.WriteResult copied = nodeWorkflowService.digestService()
                .copyAtomically(source, copy, maxBytes);
        if (!copied.sha256().equals(actual)) {
            // 复制期间源文件被改写：宁可失败也不能登记一份"没校验过"的内容
            nodeWorkflowService.deleteFileQuietly(nodeWorkflowService.storage().relativize(copy));
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件在提交过程中被修改，请重新提交");
        }

        // ④ 单事务：登记输出 + 判定 + 推进
        LocalDateTime now = NodeWorkflowService.now();
        Long committedBy = context.operator().userId();
        String copyRelPath = nodeWorkflowService.storage().relativize(copy);
        String fileName = copy.getFileName().toString();
        try {
            CommitOutcome outcome = nodeWorkflowService.transactionTemplate().execute(status -> {
                FileRecord file = new FileRecord();
                file.setProjectId(requirement.getProjectId());
                file.setRequirementId(requirement.getId());
                file.setRefType(FileRecord.REF_TYPE_NODE_OUTPUT);
                file.setOriginalName(fileName);
                file.setStorageRelPath(copyRelPath);
                file.setMimeType(null);
                file.setSizeBytes(copied.bytes());
                file.setSha256(copied.sha256());
                file.setUploadedBy(committedBy);
                file.setCreatedAt(now);
                nodeWorkflowService.fileMapper().insert(file);

                nodeWorkflowService.nodeOutputMapper().upsertCommit(node.getId(), context.outputItem().itemId(),
                        itemKey, file.getId(), committedBy, now);
                // 回读真实行：UPSERT 的更新分支下 last_insert_id 是新分配的 AUTO_INCREMENT，不能当主键
                NodeOutputRecord saved = nodeWorkflowService.nodeOutputMapper()
                        .selectByNodeAndKey(node.getId(), itemKey);
                if (saved == null) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR, "输出项登记失败：" + itemKey);
                }
                // 回填"文件 → 输出记录"的引用（同一事务）
                nodeWorkflowService.fileMapper()
                        .updateRef(file.getId(), FileRecord.REF_TYPE_NODE_OUTPUT, saved.getId());

                // 判定必须在事务内、且读到刚写的记录：半提交绝不推进
                NodeAdvanceEvaluator.Evaluation evaluation = advanceEvaluator.evaluate(node.getId(),
                        node.getInputType(), context.snapshot());
                if (!evaluation.ready()) {
                    return new CommitOutcome(saved.getVersionNo(), false, null, false,
                            evaluation.missingOutputs(), evaluation.missingInputs());
                }
                AdvanceResult advance = advance(requirement, node, now);
                return new CommitOutcome(saved.getVersionNo(), advance.advanced(), advance.nextNodeName(),
                        advance.completed(), List.of(), List.of());
            });
            if (outcome == null) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR, "提交事务未返回结果");
            }
            log.info("提交节点输出：requirementId={}, nodeId={}, itemKey={}, versionNo={}, advanced={}, next={},"
                            + " operator={}",
                    requirement.getId(), node.getId(), itemKey, outcome.versionNo(), outcome.advanced(),
                    outcome.nextNodeName(), operator == null ? OPERATOR_AGENT : operator);
            return new com.fde.workbench.service.vo.OutputCommitVO(true, outcome.versionNo(), outcome.advanced(),
                    outcome.nextNodeName(), outcome.completed(), outcome.remainingOutputs(),
                    outcome.remainingInputs());
        } catch (RuntimeException ex) {
            // 事务失败：删掉刚复制的副本，避免 outputs/ 下堆积无登记的文件
            nodeWorkflowService.deleteFileQuietly(copyRelPath);
            throw ex;
        }
    }

    /**
     * 内容未变化时的事务：不写新版本，只重新判定一次推进条件。
     * <p>
     * 判定同样放在事务里：若此刻必填项已齐备（例如必填输入刚补上），就在这里完成推进，
     * 保证"有没有推进"与"必填是否齐备"始终是同一个时刻的状态。
     *
     * @param context 提交上下文
     * @param current 当前已登记的版本
     * @return 提交结果（{@code versionNo} 为当前版本号）
     */
    private com.fde.workbench.service.vo.OutputCommitVO commitUnchanged(InternalCommitContext context,
                                                                        NodeOutputRecord current) {
        RequirementNode node = context.node();
        RequirementWithStats requirement = context.requirement();
        LocalDateTime now = NodeWorkflowService.now();
        AdvanceOutcome outcome = nodeWorkflowService.transactionTemplate().execute(status -> {
            NodeAdvanceEvaluator.Evaluation evaluation = advanceEvaluator.evaluate(node.getId(),
                    node.getInputType(), context.snapshot());
            if (!evaluation.ready()) {
                return new AdvanceOutcome(false, null, false, evaluation.missingOutputs(),
                        evaluation.missingInputs());
            }
            AdvanceResult advance = advance(requirement, node, now);
            return new AdvanceOutcome(advance.advanced(), advance.nextNodeName(), advance.completed(),
                    List.of(), List.of());
        });
        if (outcome == null) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "提交事务未返回结果");
        }
        log.info("重复提交（内容未变）：nodeId={}, itemKey={}, versionNo={}, advanced={}",
                node.getId(), current.getItemKey(), current.getVersionNo(), outcome.advanced());
        return new com.fde.workbench.service.vo.OutputCommitVO(true, current.getVersionNo(), outcome.advanced(),
                outcome.nextNodeName(), outcome.completed(), outcome.remainingOutputs(),
                outcome.remainingInputs());
    }

    /**
     * 判断输出项当前版本的文件内容是否与本次提交一致（用于"重复提交不涨版本"）。
     *
     * @param current 当前版本记录
     * @param sha256  本次内容摘要
     * @return true 表示内容完全相同
     */
    private boolean isSameContent(NodeOutputRecord current, String sha256) {
        FileRecord file = nodeWorkflowService.fileMapper().selectById(current.getFileId());
        return file != null && sha256.equals(file.getSha256());
    }

    /**
     * 节点创建（推进）本身：当前节点 done → 下一节点 current → 需求 current_seq+1 / completed。
     * <p>
     * 必须在调用方的事务内执行（{@link #commitOutput} 的 ④）。
     *
     * @param requirement 需求（{@code current_seq} 以库为准，这里用节点序列比较）
     * @param node        当前节点
     * @param now         业务时间
     * @return 推进结果
     */
    private AdvanceResult advance(RequirementWithStats requirement, RequirementNode node, LocalDateTime now) {
        List<RequirementNode> nodes = nodeWorkflowService.requirementMapper()
                .selectNodesByRequirement(requirement.getId());
        RequirementNode next = nodes.stream()
                .filter(candidate -> Objects.equals(candidate.getSeq(), node.getSeq() + 1))
                .findFirst()
                .orElse(null);

        // 已是 done 的节点不重复推进（防止对已完成节点再次提交时把 next 节点/current_seq 再推一次）
        if (RequirementNode.STATUS_DONE.equals(node.getStatus())) {
            return new AdvanceResult(false, null, false);
        }
        int done = nodeWorkflowService.requirementMapper().markNodeDone(node.getId(), null, now);
        if (done == 0) {
            // 并发：另一个事务已经把节点改走，本事务不再推进
            return new AdvanceResult(false, null, false);
        }
        if (next == null) {
            // 最后一个节点：需求整体完成（PRD AC-F4.6）
            nodeWorkflowService.requirementMapper().markRequirementCompleted(requirement.getId(), now);
            log.info("需求全部节点完成：requirementId={}", requirement.getId());
            return new AdvanceResult(true, null, true);
        }
        nodeWorkflowService.requirementMapper().markNodeCurrent(next.getId(), now);
        nodeWorkflowService.requirementMapper().advanceCurrentSeq(requirement.getId(), now);
        // 下一节点的默认会话不复制旧绑定（plugin-integration.md §6）：没有 primary 时
        // 默认会话解析会落到"需求下最近活跃会话"，历史自然连续（PRD AC-F7.3）。
        return new AdvanceResult(true, next.getName(), false);
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

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.DOWNSTREAM_UNAVAILABLE, "输出文件读取失败：" + file.getFileName());
        }
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

    /**
     * "内容未变化"分支的事务内结果。
     *
     * @param advanced         是否推进
     * @param nextNodeName     下一节点名
     * @param completed        需求是否完成
     * @param remainingOutputs 还缺的必填输出项
     * @param remainingInputs  还缺文件的必填输入项
     */
    private record AdvanceOutcome(boolean advanced, String nextNodeName, boolean completed,
                                  List<String> remainingOutputs, List<Long> remainingInputs) {
    }

    /**
     * 事务内提交结果。
     *
     * @param versionNo         版本号
     * @param advanced          是否推进
     * @param nextNodeName      下一节点名
     * @param completed         需求是否因此完成
     * @param remainingOutputs  还缺的必填输出项
     * @param remainingInputs   还缺文件的必填输入项
     */
    private record CommitOutcome(Integer versionNo, boolean advanced, String nextNodeName, boolean completed,
                                 List<String> remainingOutputs, List<Long> remainingInputs) {
    }

    /**
     * 推进结果。
     *
     * @param advanced     是否推进
     * @param nextNodeName 下一节点名称（推进到最后一节点后为 null）
     * @param completed    需求是否因此完成
     */
    private record AdvanceResult(boolean advanced, String nextNodeName, boolean completed) {
    }
}
