package com.fde.workbench.service;

import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.NodeInputItem;
import com.fde.workbench.dao.entity.NodeOutputItem;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.Workflow;
import com.fde.workbench.dao.entity.WorkflowNode;
import com.fde.workbench.dao.entity.WorkflowNodeRow;
import com.fde.workbench.dao.entity.WorkflowNodeSkill;
import com.fde.workbench.dao.mapper.RequirementMapper;
import com.fde.workbench.dao.mapper.SkillMapper;
import com.fde.workbench.dao.mapper.WorkflowMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.bo.WorkflowNodesCommand;
import com.fde.workbench.service.security.CurrentUserContext;
import com.fde.workbench.service.vo.NodeVO;
import com.fde.workbench.service.vo.WorkflowSummaryVO;
import com.fde.workbench.service.vo.WorkflowVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 流程管理服务（docs/api.md §6，PRD REQ-E1~REQ-E5）。
 * <p>
 * 权限（统一走 {@link ProjectAccessService}，本类不另写一套）：
 * <ul>
 *   <li>列表 / 创建 / 详情：项目成员即可（M）；</li>
 *   <li>编辑 / 整体保存节点 / 删除：数据创建者本人或项目 owner（C），否则 403/10202。</li>
 * </ul>
 * <b>revision 的取舍（重要）</b>：{@code wb_workflow} 表没有 {@code revision} 列，而本任务禁止改表。
 * 因此用 {@code updated_at}（DATETIME(3)，应用写入）充当并发比较基准：
 * <ul>
 *   <li>对外 {@code revision} = {@code updated_at} 的毫秒时间戳（{@link RevisionStamp}，JSON 里是整数）；</li>
 *   <li>写操作把它换算回 {@code LocalDateTime}，SQL 里用 {@code WHERE updated_at = 期望值} 做比较并交换；
 *       影响 0 行即视为冲突 → {@code 409/10402}；</li>
 *   <li>每次写都必须把 {@code updated_at} 推到<b>严格更大</b>的值（{@link #nextStamp}），
 *       否则"同一毫秒内两次保存"会让后一次的旧 revision 仍然匹配，乐观锁退化成"后写覆盖"；</li>
 *   <li>{@code revision} 缺失（null）视为参数错误 {@code 400/10001}（与项目接口"revision 必填"一致）。</li>
 * </ul>
 * 代价：revision 是"时间"而不是"版本号"，展示上永远不会是 1、2、3；并发判定精度依赖"写入时间严格递增"
 * 这一应用层约束，而不是数据库自增列。接口形状不变，未来若允许改表（加 {@code revision INT}），
 * 只需替换本类里 revision 的换算与比较，前端无需改动。
 * <p>
 * <b>被需求引用的流程能否编辑</b>：允许编辑，且只影响新建需求。需求创建时会把节点、输入项、输出项、
 * 技能按版本快照进 {@code wb_requirement_node}（PRD AC-F2.2），已有需求读的是自己的快照；
 * 只有"删除"被需求阻止（{@code wb_requirement.workflow_id} 外键 RESTRICT + api.md §6 的 409/10403）。
 * 因此本类不对"是否被需求引用"做任何编辑限制。
 * <p>
 * 事务边界：整体保存节点在校验<b>全部通过之后</b>才开事务（校验只读，失败时没有任何写入需要回滚），
 * 事务内依次是"推进流程 updated_at（占位式乐观锁）→ 节点新增/更新 → 子项重建 → seq 重排 → 删除多余节点"。
 * 删除也放在最后：seq 重排时把"即将删除的节点"一并挪进负数区，才不会被唯一索引挡住。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    /** 流程名称最大长度（与 wb_workflow.name VARCHAR(128) 一致） */
    private static final int MAX_WORKFLOW_NAME_LENGTH = 128;

    /** 说明字段最大长度（与 wb_workflow.description / wb_workflow_node.description VARCHAR(1000) 一致） */
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    /** 节点名称最大长度（与 wb_workflow_node.name VARCHAR(128) 一致） */
    private static final int MAX_NODE_NAME_LENGTH = 128;

    /** 输入 / 输出项名称最大长度（与 wb_node_input_item.name / wb_node_output_item.name VARCHAR(128) 一致） */
    private static final int MAX_ITEM_NAME_LENGTH = 128;

    /** 输入 / 输出项说明最大长度（与两表的 description VARCHAR(500) 一致） */
    private static final int MAX_ITEM_DESCRIPTION_LENGTH = 500;

    /**
     * 输出项稳定键格式（docs/api.md §6 校验规则 2）：小写字母或数字开头，后接小写字母 / 数字 / 连字符，
     * 总长 1–64。与 wb_node_output_item.item_key VARCHAR(64) 对齐；
     * 不允许大写与下划线，是因为它会被用到模型工具参数与文件路径（{@code .workbench/<node>/<itemKey>.<ext>}）上，
     * 放宽字符集只会带来大小写不敏感文件系统与 URL 转义的麻烦。
     */
    private static final Pattern ITEM_KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /** 合法的输入类型（与 wb_workflow_node.input_type 注释一致） */
    private static final Set<String> INPUT_TYPES = Set.of(WorkflowNode.INPUT_TYPE_FREE, WorkflowNode.INPUT_TYPE_FIXED);

    /** 合法的文件类型（与 wb_node_input_item / wb_node_output_item 的 file_kind 注释一致） */
    private static final Set<String> FILE_KINDS = Set.of("text", "document", "any");

    /** 文件类型缺省值 */
    private static final String DEFAULT_FILE_KIND = "any";

    private final WorkflowMapper workflowMapper;

    /** 只用于"技能必须属于本项目且未删除"的校验（全部技能一次查出，不逐条查库） */
    private final SkillMapper skillMapper;

    /** 只用于删除前的引用计数（T3-1 只引入 countByWorkflowId，不改需求其它逻辑） */
    private final RequirementMapper requirementMapper;

    private final ProjectAccessService projectAccessService;

    private final CurrentUserContext currentUserContext;

    private final TransactionTemplate transactionTemplate;

    public WorkflowService(WorkflowMapper workflowMapper,
                           SkillMapper skillMapper,
                           RequirementMapper requirementMapper,
                           ProjectAccessService projectAccessService,
                           CurrentUserContext currentUserContext,
                           TransactionTemplate transactionTemplate) {
        this.workflowMapper = workflowMapper;
        this.skillMapper = skillMapper;
        this.requirementMapper = requirementMapper;
        this.projectAccessService = projectAccessService;
        this.currentUserContext = currentUserContext;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------
    // 列表 / 创建
    // ------------------------------------------------------------------

    /**
     * 项目流程列表（项目成员即可，含节点数）。
     *
     * @param projectId 项目 ID
     * @return 流程列表（创建时间倒序）
     * @throws BizException 项目不存在 404/10301；非成员 403/10201
     */
    public List<WorkflowSummaryVO> list(Long projectId) {
        Long userId = currentUserContext.require().userId();
        projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, userId);
        return workflowMapper.selectByProject(projectId).stream().map(WorkflowSummaryVO::of).toList();
    }

    /**
     * 创建流程：只写名称与说明（节点由随后的"整体保存节点"写入，见 api.md §6 的设计）。
     * <p>
     * 校验顺序：项目存在 → 我是成员 → 名称/说明合法。名称不做项目内唯一限制：
     * api.md 只对"同名技能"定义 10401，流程允许同名（例如"临时流程"）。
     *
     * @param projectId   项目 ID
     * @param name        流程名称（1–128 字符）
     * @param description 流程说明，可空（≤1000 字符）
     * @return 新流程（节点数为 0）
     * @throws BizException 404/10301、403/10201、400/10001
     */
    public WorkflowSummaryVO create(Long projectId, String name, String description) {
        CurrentUser user = currentUserContext.require();
        projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, user.userId());

        String trimmedName = requireText(name, MAX_WORKFLOW_NAME_LENGTH, "流程名称不能为空", "流程名称不能超过 "
                + MAX_WORKFLOW_NAME_LENGTH + " 个字符");
        String desc = normalizeOptionalText(description, MAX_DESCRIPTION_LENGTH,
                "流程说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");

        LocalDateTime now = now();
        Workflow workflow = new Workflow();
        workflow.setProjectId(projectId);
        workflow.setName(trimmedName);
        workflow.setDescription(desc);
        workflow.setCreatedBy(user.userId());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowMapper.insert(workflow);
        log.info("创建流程：projectId={}, workflowId={}, 操作人={}", projectId, workflow.getId(), user.userId());

        return toSummary(requireExistingWorkflow(workflow.getId()));
    }

    /**
     * 实体 → 列表项视图。
     * <p>
     * 这里 {@code nodeCount} 恒为 0 且正确：本方法只用于"刚创建、还没有节点"的流程
     * （节点由随后的整体保存写入，且整体保存不经过本方法）。列表接口用带统计列的
     * {@link WorkflowSummaryVO#of(com.fde.workbench.dao.entity.WorkflowWithStats)}，两者字段与含义一致。
     *
     * @param workflow 流程实体
     * @return 列表项视图
     */
    private static WorkflowSummaryVO toSummary(Workflow workflow) {
        return new WorkflowSummaryVO(
                workflow.getId(),
                workflow.getProjectId(),
                workflow.getName(),
                workflow.getDescription(),
                0,
                RevisionStamp.of(workflow.getUpdatedAt()),
                workflow.getCreatedBy(),
                workflow.getCreatedAt().atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime(),
                workflow.getUpdatedAt().atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime());
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    /**
     * 流程详情（项目成员即可）：含全部节点、输入项、输出项、技能，全部按 seq 排序。
     *
     * @param workflowId 流程 ID
     * @return 流程详情
     * @throws BizException 流程不存在 404/10301；项目不存在 404/10301；非成员 403/10201
     */
    public WorkflowVO detail(Long workflowId) {
        Long userId = currentUserContext.require().userId();
        Workflow workflow = requireExistingWorkflow(workflowId);
        projectAccessService.requireExistingProject(workflow.getProjectId());
        projectAccessService.requireMember(workflow.getProjectId(), userId);
        return WorkflowVO.of(workflow, loadNodes(workflowId));
    }

    /**
     * 组装节点视图：一次 LEFT JOIN 查询取回全部子项，再按节点归并。
     * <p>
     * 归并时三类子项各自按 id 去重：展平行来自笛卡尔积（输出项 × 输入项 × 技能），
     * 不去重会出现"同一个输出项出现三次"。排序交给 SQL 的 ORDER BY（各类子项按自己的 seq）。
     *
     * @param workflowId 流程 ID
     * @return 节点视图（按 seq 升序）
     */
    private List<NodeVO> loadNodes(Long workflowId) {
        List<WorkflowNodeRow> rows = workflowMapper.selectNodeDetails(workflowId);
        // LinkedHashMap 保留 SQL 的节点顺序（seq 升序）
        Map<Long, NodeBuilder> builders = new LinkedHashMap<>();
        for (WorkflowNodeRow row : rows) {
            NodeBuilder builder = builders.computeIfAbsent(row.getNodeId(), ignored -> new NodeBuilder(row));
            builder.add(row);
        }
        return builders.values().stream().map(NodeBuilder::build).toList();
    }

    /**
     * 节点归并器：把展平行去重成 {@link NodeVO}。
     */
    private static final class NodeBuilder {

        private final Long id;
        private final Integer seq;
        private final String name;
        private final String description;
        private final String inputType;
        private final Map<Long, NodeVO.SkillRefVO> skills = new LinkedHashMap<>();
        private final Map<Long, NodeVO.InputVO> inputs = new LinkedHashMap<>();
        private final Map<Long, NodeVO.OutputVO> outputs = new LinkedHashMap<>();

        private NodeBuilder(WorkflowNodeRow row) {
            this.id = row.getNodeId();
            this.seq = row.getNodeSeq();
            this.name = row.getNodeName();
            this.description = row.getNodeDescription();
            this.inputType = row.getNodeInputType();
        }

        private void add(WorkflowNodeRow row) {
            if (row.getSkillId() != null) {
                skills.putIfAbsent(row.getSkillId(),
                        new NodeVO.SkillRefVO(row.getSkillId(), row.getSkillName(),
                                row.getSkillSeq() == null ? 1 : row.getSkillSeq()));
            }
            if (row.getInputItemId() != null) {
                inputs.putIfAbsent(row.getInputItemId(),
                        new NodeVO.InputVO(row.getInputItemId(), row.getInputSeq(), row.getInputName(),
                                row.getInputDescription(), row.getInputFileKind(),
                                Boolean.TRUE.equals(row.getInputRequired())));
            }
            if (row.getOutputItemId() != null) {
                outputs.putIfAbsent(row.getOutputItemId(),
                        new NodeVO.OutputVO(row.getOutputItemId(), row.getOutputSeq(), row.getOutputItemKey(),
                                row.getOutputName(), row.getOutputDescription(), row.getOutputFileKind(),
                                Boolean.TRUE.equals(row.getOutputRequired())));
            }
        }

        private NodeVO build() {
            return new NodeVO(id, seq, name, description, inputType,
                    List.copyOf(skills.values()), List.copyOf(inputs.values()), List.copyOf(outputs.values()));
        }
    }

    // ------------------------------------------------------------------
    // 更新名称 / 说明
    // ------------------------------------------------------------------

    /**
     * 更新流程名称 / 说明：创建者或 owner，带 revision 乐观锁。
     *
     * @param workflowId  流程 ID
     * @param name        新名称
     * @param description 新说明，可空
     * @param revision    前端持有的版本号
     * @return 更新后的流程详情
     * @throws BizException 404/10301、403/10201、403/10202、400/10001、409/10402
     */
    public WorkflowVO update(Long workflowId, String name, String description, RevisionStamp revision) {
        Workflow workflow = requireEditableWorkflow(workflowId);
        String trimmedName = requireText(name, MAX_WORKFLOW_NAME_LENGTH, "流程名称不能为空", "流程名称不能超过 "
                + MAX_WORKFLOW_NAME_LENGTH + " 个字符");
        String desc = normalizeOptionalText(description, MAX_DESCRIPTION_LENGTH,
                "流程说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        LocalDateTime expected = requireRevision(revision);

        int affected = workflowMapper.updateWithRevision(workflowId, trimmedName, desc, expected,
                nextStamp(expected));
        if (affected == 0) {
            throw revisionConflict(workflowId, expected);
        }
        return WorkflowVO.of(requireExistingWorkflow(workflowId), loadNodes(workflowId));
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /**
     * 删除流程：创建者或 owner，带 revision 乐观锁；<b>被需求引用时 409/10403</b>。
     * <p>
     * 为什么是逻辑删除：{@code fk_requirement_workflow} 是 RESTRICT，而且历史需求要追溯来源流程；
     * 逻辑删除后列表与详情都不再出现（{@code selectById} 过滤 {@code deleted=0}），
     * 节点与子项原样保留，供历史需求与审计使用。
     * <p>
     * 引用口径：{@code wb_requirement.workflow_id} 存在任何记录即不可删（不区分需求状态）。
     *
     * @param workflowId 流程 ID
     * @param revision   前端持有的版本号（必填）
     * @throws BizException 404/10301、403/10201、403/10202、400/10001、409/10403、409/10402
     */
    public void delete(Long workflowId, RevisionStamp revision) {
        requireEditableWorkflow(workflowId);
        LocalDateTime expected = requireRevision(revision);
        // 引用计数与逻辑删除放在同一事务：否则"计数通过 → 别人刚好新建了引用该流程的需求 → 删除成功"
        // 之间会留下一个悬空引用的窗口。
        transactionTemplate.executeWithoutResult(status -> {
            long referenced = requirementMapper.countByWorkflowId(workflowId);
            if (referenced > 0) {
                throw BizException.of(ErrorCode.REFERENCED,
                        "流程已被 " + referenced + " 个需求引用，不能删除");
            }
            if (workflowMapper.markDeleted(workflowId, expected, nextStamp(expected)) != 1) {
                throw revisionConflict(workflowId, expected);
            }
        });
        log.info("删除流程（逻辑删除）：workflowId={}", workflowId);
    }

    // ------------------------------------------------------------------
    // 整体保存节点列表（核心）
    // ------------------------------------------------------------------

    /**
     * 整体保存节点列表（docs/api.md §6 {@code PUT /api/workflows/{id}/nodes}）。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>权限（C）+ revision 必填；</li>
     *   <li>把请求体规整成"合法节点列表"：逐个节点做全部校验（schema / 名称 / 输出项 / itemKey /
     *       fixed 输入项 / 技能归属），任一失败整体 400/10001，错误文案带上"第 N 个节点『名称』的哪一项"；</li>
     *   <li>{@code nodeId} 若给了，必须属于本流程，否则 400/10001（防止把别的流程的节点"搬"进来）；</li>
     *   <li>开事务：新增节点 / 更新已有节点 → 整表重建技能关联、输入项、输出项 → seq 重排（三步法）
     *       → 删除请求里不存在的节点（先删子表再删节点）→ 推进流程 updated_at；</li>
     *   <li>事务返回后重新读详情，返回与 GET 详情完全一致的结构。</li>
     * </ol>
     *
     * @param workflowId 流程 ID
     * @param command    请求体（revision + 节点数组，数组顺序即执行顺序）
     * @return 保存后的流程详情
     * @throws BizException 404/10301、403/10201、403/10202、400/10001、409/10402
     */
    public WorkflowVO saveNodes(Long workflowId, WorkflowNodesCommand command) {
        Workflow workflow = requireEditableWorkflow(workflowId);
        if (command == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        LocalDateTime expected = requireRevision(command.revision());

        LocalDateTime stamp = nextStamp(expected);
        // ① 规整 + 校验（只读，不产生任何写入）
        List<PreparedNode> prepared = prepareNodes(workflowId, command.nodes());
        // ② 技能归属校验：一次取出本项目全部可见技能，避免逐条查库
        validateSkills(workflow.getProjectId(), prepared);

        // ③ 事务：所有写入要么全成、要么全不成
        transactionTemplate.executeWithoutResult(status -> persistNodes(workflowId, prepared, expected, stamp));

        // 重读一次：revision 必须是库里刚刚写入的那个值（不能拿请求里的 stamp 凑）
        return WorkflowVO.of(requireExistingWorkflow(workflowId), loadNodes(workflowId));
    }

    /**
     * 校验单个节点并把文本字段规整好（去掉首尾空白、缺省值补齐）。
     *
     * @param workflowId 流程 ID
     * @param rawNodes   请求中的节点数组，可空
     * @return 规整后的节点（下标 + 1 即目标 seq）
     * @throws BizException 400/10001（文案指明第几个节点 / 哪一项）
     */
    private List<PreparedNode> prepareNodes(Long workflowId, List<WorkflowNodesCommand.Node> rawNodes) {
        // 规则 1：至少 1 个节点
        if (rawNodes == null || rawNodes.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "流程至少要有 1 个节点");
        }
        // 已有节点 ID 的归属清单：nodeId 必须属于本流程，否则说明前端拿错了流程的数据
        Map<Long, Integer> originSeqs = new HashMap<>();
        for (WorkflowNode existing : workflowMapper.selectNodesByWorkflow(workflowId)) {
            originSeqs.put(existing.getId(), existing.getSeq());
        }

        List<PreparedNode> prepared = new ArrayList<>(rawNodes.size());
        Set<Long> seenNodeIds = new HashSet<>();
        for (int i = 0; i < rawNodes.size(); i++) {
            WorkflowNodesCommand.Node raw = rawNodes.get(i);
            // 数组下标 + 1 就是执行顺序（规则 5：数组顺序即执行顺序）
            int seq = i + 1;
            String label = "第 " + seq + " 个节点";
            if (raw == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, label + "不能为空");
            }
            String name = raw.name() == null ? "" : raw.name().trim();
            String nodeLabel = name.isEmpty() ? label : label + "『" + name + "』";

            // 规则 1：节点名称非空且 ≤128
            name = requireText(raw.name(), MAX_NODE_NAME_LENGTH,
                    nodeLabel + "的名称不能为空",
                    nodeLabel + "的名称不能超过 " + MAX_NODE_NAME_LENGTH + " 个字符");
            String description = normalizeOptionalText(raw.description(), MAX_DESCRIPTION_LENGTH,
                    nodeLabel + "的说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
            String inputType = raw.inputType() == null ? "" : raw.inputType().trim();
            if (!INPUT_TYPES.contains(inputType)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        nodeLabel + "的输入类型只能是 free 或 fixed");
            }

            Long nodeId = raw.nodeId();
            if (nodeId != null) {
                if (!originSeqs.containsKey(nodeId)) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            nodeLabel + "的 nodeId=" + nodeId + " 不属于该流程");
                }
                if (!seenNodeIds.add(nodeId)) {
                    // 同一个节点在数组里出现两次：seq 重排会给出两个不同的顺序，语义不明确，直接拒绝
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            nodeLabel + "的 nodeId=" + nodeId + " 在请求中重复出现");
                }
            }

            // 规则 2：输出项至少 1 项，itemKey 节点内唯一且格式合法
            List<PreparedItem> outputs = prepareItems(raw.outputs(), true, nodeLabel);
            if (outputs.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, nodeLabel + "至少要有 1 个输出项");
            }

            // 规则 3：fixed 至少 1 个固定输入项；free 忽略 inputs（库里也不留，避免"切回 fixed 时冒出旧输入项"）
            List<PreparedItem> inputs = List.of();
            if (WorkflowNode.INPUT_TYPE_FIXED.equals(inputType)) {
                inputs = prepareItems(raw.inputs(), false, nodeLabel);
                if (inputs.isEmpty()) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            nodeLabel + "的输入类型为 fixed 时至少要有 1 个固定输入项");
                }
            }

            prepared.add(new PreparedNode(nodeId, seq, originSeqs.get(nodeId), name, description, inputType,
                    normalizeSkillIds(raw.skillIds(), nodeLabel), inputs, outputs));
        }
        return prepared;
    }

    /**
     * 规整输入项 / 输出项列表。
     *
     * @param rawItems  请求中的列表，可空
     * @param output    true 为输出项（需要 itemKey），false 为输入项
     * @param nodeLabel 节点定位文案（拼进错误信息）
     * @return 规整后的列表（顺序即 seq）
     * @throws BizException 400/10001
     */
    private static List<PreparedItem> prepareItems(List<WorkflowNodesCommand.Item> rawItems, boolean output,
                                                   String nodeLabel) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }
        String kind = output ? "输出项" : "输入项";
        List<PreparedItem> items = new ArrayList<>(rawItems.size());
        // 节点内 itemKey 唯一：用 set 记录已出现的 key（只针对输出项）
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < rawItems.size(); i++) {
            WorkflowNodesCommand.Item raw = rawItems.get(i);
            String itemLabel = nodeLabel + "的第 " + (i + 1) + " 个" + kind;
            if (raw == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, itemLabel + "不能为空");
            }
            String name = requireText(raw.name(), MAX_ITEM_NAME_LENGTH,
                    itemLabel + "的名称不能为空",
                    itemLabel + "的名称不能超过 " + MAX_ITEM_NAME_LENGTH + " 个字符");
            String description = normalizeOptionalText(raw.description(), MAX_ITEM_DESCRIPTION_LENGTH,
                    itemLabel + "的说明不能超过 " + MAX_ITEM_DESCRIPTION_LENGTH + " 个字符");
            String fileKind = raw.fileKind() == null || raw.fileKind().isBlank()
                    ? DEFAULT_FILE_KIND : raw.fileKind().trim();
            if (!FILE_KINDS.contains(fileKind)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        itemLabel + "『" + name + "』的文件类型只能是 text、document 或 any");
            }
            // required 缺省为 true：输出项默认必填（未提交则不推进），与建表默认值一致
            boolean required = raw.required() == null || raw.required();

            String itemKey = null;
            if (output) {
                String key = raw.itemKey() == null ? "" : raw.itemKey().trim();
                if (!ITEM_KEY_PATTERN.matcher(key).matches()) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            itemLabel + "『" + name + "』的 itemKey 必须匹配 ^[a-z0-9][a-z0-9-]{0,63}$，当前为『"
                                    + key + "』");
                }
                if (!seenKeys.add(key)) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            itemLabel + "『" + name + "』的 itemKey『" + key + "』在本节点内重复");
                }
                itemKey = key;
            }
            items.add(new PreparedItem(itemKey, name, description, fileKind, required));
        }
        return items;
    }

    /**
     * 规整技能 ID 列表：去空、去重（{@code uk_node_skill} 是唯一索引），保留首次出现的顺序。
     *
     * @param skillIds  请求中的技能 ID
     * @param nodeLabel 节点定位文案
     * @return 去重后的技能 ID（可空列表）
     * @throws BizException 400/10001（负数或 0 的 ID）
     */
    private static List<Long> normalizeSkillIds(List<Long> skillIds, String nodeLabel) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long skillId : skillIds) {
            if (skillId == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, nodeLabel + "的技能列表里有空值");
            }
            if (skillId <= 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID, nodeLabel + "的技能 ID 不合法：" + skillId);
            }
            unique.add(skillId);
        }
        return List.copyOf(unique);
    }

    /**
     * 规则 4：技能必须属于本项目且未被逻辑删除。
     * <p>
     * 判定基准取 {@link SkillMapper#selectByProject}（已过滤 {@code deleted=0}，与原 {@link SkillMapper#selectById}
     * 的"排除逻辑删除"口径一致），因此"不在返回集合里"同时覆盖"跨项目"与"已删除"两种情况：
     * 两者对用户都是"不能用作节点技能"，合并成一条文案即可，不必再按 id 查一次去区分。
     * 一次查询拿到本项目全部技能，而不是每个 skillId 查一次，避免节点多、技能多时的 N+1。
     *
     * @param projectId 项目 ID
     * @param prepared  规整后的节点
     * @throws BizException 400/10001
     */
    private void validateSkills(Long projectId, List<PreparedNode> prepared) {
        Set<Long> projectSkillIds = new HashSet<>();
        skillMapper.selectByProject(projectId).forEach(skill -> projectSkillIds.add(skill.getId()));

        for (PreparedNode node : prepared) {
            for (Long skillId : node.skillIds()) {
                if (projectSkillIds.contains(skillId)) {
                    continue;
                }
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "第 " + node.seq() + " 个节点『" + node.name() + "』引用的技能 " + skillId
                                + " 不属于本项目或已被删除");
            }
        }
    }

    // ------------------------------------------------------------------
    // 事务内：真正写库
    // ------------------------------------------------------------------

    /**
     * 事务内整体落库。顺序经过设计，任何一步失败都整体回滚：
     * <ol>
     *   <li>先 {@code UPDATE wb_workflow ... WHERE updated_at = 期望值}：这是乐观锁的"占位"动作，
     *       放在最前面可以在拿到行锁后立刻与其他人串行化，避免长事务做了一堆活才发现版本冲突；</li>
     *   <li>新增节点：{@code nodeId=null} 的节点先插入，并临时占用一个负数 seq（见下面第 ② 步的说明）；</li>
     *   <li>更新已有节点内容；</li>
     *   <li>整表重建技能关联 / 输入项 / 输出项：这三样都按"请求里的列表就是全部"处理，
     *       且输入项、输出项没有可稳定匹配的键（输入项根本没有 key），整体重建比逐个 diff 更不容易漏；</li>
     *   <li>seq 重排（见 {@link #reassignSeqs}）：先把全部节点（含待删除的）挪进新的负数区，
     *       再把保留节点写回 1..n；</li>
     *   <li>删除请求里不存在的节点：此时它们都停在负数序号上，按外键 RESTRICT 的顺序
     *       先删技能关联 → 输入项 → 输出项，最后删节点。</li>
     * </ol>
     *
     * @param workflowId 流程 ID
     * @param prepared   规整后的节点
     * @param expected   期望的 updated_at（乐观锁比较值）
     * @param stamp      本次写入的 updated_at（严格大于 expected）
     */
    private void persistNodes(Long workflowId, List<PreparedNode> prepared,
                              LocalDateTime expected, LocalDateTime stamp) {
        // ① 占位式乐观锁：影响 0 行说明有人在本次请求期间改过流程（或已删除），直接 409
        if (workflowMapper.touchUpdatedAt(workflowId, expected, stamp) != 1) {
            throw revisionConflict(workflowId, expected);
        }

        // ② 新增 + 更新
        List<WorkflowNode> existingNodes = workflowMapper.selectNodesByWorkflow(workflowId);
        // 新增节点的临时 seq 取"当前最小 seq"之下连续的负数，保证互不相同、也不与既有节点或残留负数冲突，
        // 随后第 ④ 步会把它们统一改成 1..n 的正式序号（删除过节点的流程会残留负数，这里一并跳开）。
        int tempCursor = existingNodes.stream().map(WorkflowNode::getSeq).filter(Objects::nonNull)
                .min(Integer::compareTo).orElse(0) - 1;
        Set<Long> keptNodeIds = new HashSet<>();
        for (PreparedNode node : prepared) {
            if (node.nodeId() == null) {
                WorkflowNode created = new WorkflowNode();
                created.setWorkflowId(workflowId);
                created.setSeq(tempCursor--);
                created.setName(node.name());
                created.setDescription(node.description());
                created.setInputType(node.inputType());
                created.setCreatedAt(stamp);
                created.setUpdatedAt(stamp);
                workflowMapper.insertNode(created);
                node.assignedId = created.getId();
            } else {
                WorkflowNode updated = new WorkflowNode();
                updated.setId(node.nodeId());
                updated.setWorkflowId(workflowId);
                updated.setName(node.name());
                updated.setDescription(node.description());
                updated.setInputType(node.inputType());
                updated.setUpdatedAt(stamp);
                workflowMapper.updateNodeContent(updated);
                node.assignedId = node.nodeId();
            }
            keptNodeIds.add(node.assignedId);
        }

        // ③ 重建子项：先整表清空（技能关联 → 输入项 → 输出项），再按请求顺序插入
        workflowMapper.deleteNodeSkillsByWorkflow(workflowId);
        workflowMapper.deleteInputItemsByWorkflow(workflowId);
        workflowMapper.deleteOutputItemsByWorkflow(workflowId);
        for (PreparedNode node : prepared) {
            int skillSeq = 1;
            for (Long skillId : node.skillIds()) {
                WorkflowNodeSkill link = new WorkflowNodeSkill();
                link.setNodeId(node.assignedId);
                link.setSkillId(skillId);
                link.setSeq(skillSeq++);
                workflowMapper.insertNodeSkill(link);
            }
            int inputSeq = 1;
            for (PreparedItem item : node.inputs()) {
                NodeInputItem input = new NodeInputItem();
                input.setNodeId(node.assignedId);
                input.setSeq(inputSeq++);
                input.setName(item.name());
                input.setDescription(item.description());
                input.setFileKind(item.fileKind());
                input.setRequired(item.required() ? 1 : 0);
                input.setCreatedAt(stamp);
                workflowMapper.insertInputItem(input);
            }
            int outputSeq = 1;
            for (PreparedItem item : node.outputs()) {
                NodeOutputItem output = new NodeOutputItem();
                output.setNodeId(node.assignedId);
                output.setSeq(outputSeq++);
                output.setItemKey(item.itemKey());
                output.setName(item.name());
                output.setDescription(item.description());
                output.setFileKind(item.fileKind());
                output.setRequired(item.required() ? 1 : 0);
                output.setCreatedAt(stamp);
                workflowMapper.insertOutputItem(output);
            }
        }

        // ④ seq 重排：只针对保留下来的节点
        List<WorkflowNode> ordered = new ArrayList<>(prepared.size());
        for (PreparedNode node : prepared) {
            WorkflowNode seqNode = new WorkflowNode();
            seqNode.setId(node.assignedId);
            seqNode.setSeq(node.seq());
            ordered.add(seqNode);
        }
        reassignSeqs(workflowId, ordered, stamp);

        // ⑤ 删除请求里不存在的节点——外键 RESTRICT 的顺序：技能关联 → 输入项 → 输出项 → 节点
        List<Long> removedIds = existingNodes.stream()
                .map(WorkflowNode::getId)
                .filter(id -> !keptNodeIds.contains(id))
                .toList();
        if (!removedIds.isEmpty()) {
            workflowMapper.deleteNodeSkills(removedIds);
            workflowMapper.deleteInputItems(removedIds);
            workflowMapper.deleteOutputItems(removedIds);
            workflowMapper.deleteNodes(workflowId, removedIds);
        }

        log.info("整体保存流程节点：workflowId={}, 节点数={}, 新增={}, 删除={}", workflowId, prepared.size(),
                prepared.stream().filter(node -> node.nodeId() == null).count(), removedIds.size());
    }

    /**
     * seq 三步法重排（为什么必须三步：{@code uk_node_workflow_seq(workflow_id, seq)} 是唯一索引，
     * MySQL 逐行校验、没有语句级延迟约束，直接把两个节点互换 seq 会在半途撞索引）。
     * <ol>
     *   <li>把本流程<b>全部</b>节点的 seq 写到负数区间（互不相同的负数，与任何正式序号都不冲突）；</li>
     *   <li>把保留下来的节点按请求顺序写回 1..n（此时旧序号已被清空，不会撞）；</li>
     *   <li>被删除的节点此时仍停在负数序号上，随后在第 ⑤ 步被物理删除。</li>
     * </ol>
     *
     * @param workflowId 流程 ID
     * @param ordered    保留节点（id + 目标 seq）
     * @param stamp      更新时间
     */
    private void reassignSeqs(Long workflowId, List<WorkflowNode> ordered, LocalDateTime stamp) {
        List<WorkflowNode> allNodes = workflowMapper.selectNodesByWorkflow(workflowId);
        // 第一步：把该流程【全部】节点（含马上要删的）挪到一段全新的负数区。
        // 为什么必须先把"全部"节点挪走：uk_node_workflow_seq(workflow_id, seq) 是唯一索引，
        // 若保留节点直接改写 1..n，还没轮到的那一行仍占着目标序号就会撞（实测撞过）。
        // 起点按"当前最小 seq（含新插入节点的负数）再减 1"计算，保证与任何现存序号都不重叠。
        int offset = allNodes.stream().map(WorkflowNode::getSeq).filter(Objects::nonNull)
                .min(Integer::compareTo).orElse(0) - 1;
        // 负数同样按 seq 顺序递减赋值：MySQL 按 WHERE 找到的索引扫行，实际处理顺序既可能正序也可能倒序，
        // 递减赋值能保证"已处理行的新值"永远小于"未处理行的旧值"，两种方向都不撞索引
        // （实测第一批保存时按倒序处理，-k-1 正好撞上还没轮到、仍停在 -k-1 的行）。
        List<WorkflowNode> parked = new ArrayList<>(allNodes.size());
        for (WorkflowNode node : allNodes) {
            WorkflowNode parkedNode = new WorkflowNode();
            parkedNode.setId(node.getId());
            parkedNode.setSeq(offset--);
            parked.add(parkedNode);
        }
        if (!parked.isEmpty()) {
            workflowMapper.updateNodeSeqs(workflowId, parked, stamp);
        }
        // 第二步：保留节点写回正式序号 1..n。请求顺序只决定"谁拿哪个序号"，赋值本身仍按递减
        // （n, n-1, ..., 1）进行，理由同上：此时其余行都在负数上，不会与 1..n 交叉。
        List<WorkflowNode> sequenced = new ArrayList<>(ordered.size());
        for (WorkflowNode node : ordered) {
            WorkflowNode target = new WorkflowNode();
            target.setId(node.getId());
            target.setSeq(node.getSeq());
            sequenced.add(target);
        }
        // 按目标序号从大到小下发：这样即使 MySQL 正序处理，也是先处理大序号行（新值是尚未占用的更大值），
        // 倒序处理时同样是递减，两种方向都不撞。
        sequenced.sort(Comparator.comparing(WorkflowNode::getSeq).reversed());
        if (!sequenced.isEmpty()) {
            workflowMapper.updateNodeSeqs(workflowId, sequenced, stamp);
        }
    }

    // ------------------------------------------------------------------
    // 公共校验 / 工具
    // ------------------------------------------------------------------

    /**
     * 取流程并判定"可编辑"（权限 C：创建者或 owner）。
     *
     * @param workflowId 流程 ID
     * @return 流程实体
     * @throws BizException 流程不存在 404/10301；项目不存在 404/10301；非成员 403/10201；
     *                      非创建者且非 owner 403/10202
     */
    private Workflow requireEditableWorkflow(Long workflowId) {
        CurrentUser user = currentUserContext.require();
        Workflow workflow = requireExistingWorkflow(workflowId);
        ProjectWithStats project = projectAccessService.requireExistingProject(workflow.getProjectId());
        projectAccessService.requireMember(workflow.getProjectId(), user.userId());
        boolean isCreator = Objects.equals(workflow.getCreatedBy(), user.userId());
        if (!isCreator && !projectAccessService.isOwner(project, user.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有流程创建者或项目 owner 可以修改该流程");
        }
        return workflow;
    }

    /**
     * 取未删除的流程。
     *
     * @param workflowId 流程 ID
     * @return 流程
     * @throws BizException 404/10301
     */
    private Workflow requireExistingWorkflow(Long workflowId) {
        Workflow workflow = workflowId == null ? null : workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "流程不存在");
        }
        return workflow;
    }

    /**
     * 解析 revision：缺失视为参数错误；换算成库中 {@code updated_at} 的比较值。
     *
     * @param revision 版本号（= updated_at 毫秒时间戳）
     * @return {@code updated_at} 比较值
     * @throws BizException 400/10001
     */
    private static LocalDateTime requireRevision(RevisionStamp revision) {
        if (revision == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "缺少 revision（请先获取流程详情，再带回其 revision）");
        }
        return revision.toUpdatedAt();
    }

    /**
     * revision 冲突：先确认流程还在（不在就是 404），否则 409/10402。
     * <p>
     * 判定方式是"精确相等"，而不是 {@code updated_at > revision} 这类区间比较：相等才能精确表达
     * "我提交时的版本就是我读到的这个版本"；区间比较在时间戳相同（同一毫秒）时会误判为不冲突。
     *
     * @param workflowId 流程 ID
     * @param expected   期望的 updated_at
     * @return 抛出用的异常（409/10402 或 404/10301）
     */
    private BizException revisionConflict(Long workflowId, LocalDateTime expected) {
        Workflow current = workflowId == null ? null : workflowMapper.selectById(workflowId);
        if (current == null) {
            return BizException.of(ErrorCode.NOT_FOUND, "流程不存在");
        }
        log.info("流程 revision 冲突：workflowId={}, 期望 updated_at={}，实际={}",
                workflowId, expected, current.getUpdatedAt());
        return BizException.of(ErrorCode.REVISION_CONFLICT, "流程已被他人修改，请刷新后重试");
    }

    /**
     * 计算本次写入的 {@code updated_at}：当前时间，但<b>必须严格大于</b>比较基准。
     * <p>
     * 这是"用 updated_at 当 revision"最关键的一条约束：如果新值不大于旧值，下一次带着旧 revision 的请求
     * 仍然能匹配上，乐观锁就失效了。墙钟回拨（NTP 校时）或同一毫秒内连续写入都会触发，因此这里统一
     * {@code max(now, expected + 1ms)}。
     *
     * @param expected 比较基准（旧的 updated_at）
     * @return 新的 updated_at
     */
    private static LocalDateTime nextStamp(LocalDateTime expected) {
        LocalDateTime candidate = now();
        if (candidate.isAfter(expected)) {
            return candidate;
        }
        return expected.plus(Duration.ofMillis(1));
    }

    /**
     * 当前业务时间。
     * <p>
     * 库表时间列精度为 DATETIME(3)，这里显式截断到毫秒：{@code LocalDateTime.now()} 带纳秒，
     * 直接写入会被 MySQL 四舍五入到毫秒，导致"写入值"与"回读值"不一致，进而让下一次乐观锁比较失败。
     *
     * @return 截断到毫秒的当前时间
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 必填文本校验（去首尾空白）。
     *
     * @param value     原始值
     * @param maxLength 最大长度
     * @param blankMsg  为空的提示
     * @param longMsg   超长的提示
     * @return 规整后的文本
     * @throws BizException 400/10001
     */
    private static String requireText(String value, int maxLength, String blankMsg, String longMsg) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, blankMsg);
        }
        if (trimmed.length() > maxLength) {
            throw BizException.of(ErrorCode.PARAM_INVALID, longMsg);
        }
        return trimmed;
    }

    /**
     * 可空文本规整：空白 → null；超长直接拒绝（避免 MySQL 非严格模式下被静默截断）。
     *
     * @param value     原始值
     * @param maxLength 最大长度
     * @param longMsg   超长提示
     * @return 规整后的文本或 null
     * @throws BizException 400/10001
     */
    private static String normalizeOptionalText(String value, int maxLength, String longMsg) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw BizException.of(ErrorCode.PARAM_INVALID, longMsg);
        }
        return trimmed;
    }

    /**
     * 规整后的节点（事务内使用 {@link #assignedId} 记录新增节点的自增 ID）。
     *
     * @param nodeId      请求中的节点 ID（null 表示新增）
     * @param seq         目标执行顺序（= 数组下标 + 1）
     * @param originSeq   原执行顺序（仅用于日志排查）
     * @param name        名称
     * @param description 说明
     * @param inputType   free / fixed
     * @param skillIds    去重后的技能 ID（顺序即加载顺序）
     * @param inputs      固定输入项（free 时为空）
     * @param outputs     输出项（非空）
     */
    private static final class PreparedNode {

        private final Long nodeId;
        private final int seq;
        private final Integer originSeq;
        private final String name;
        private final String description;
        private final String inputType;
        private final List<Long> skillIds;
        private final List<PreparedItem> inputs;
        private final List<PreparedItem> outputs;

        /** 事务内回填的实际节点 ID（新增节点为自增 ID） */
        private Long assignedId;

        private PreparedNode(Long nodeId, int seq, Integer originSeq, String name, String description,
                             String inputType, List<Long> skillIds, List<PreparedItem> inputs,
                             List<PreparedItem> outputs) {
            this.nodeId = nodeId;
            this.seq = seq;
            this.originSeq = originSeq;
            this.name = name;
            this.description = description;
            this.inputType = inputType;
            this.skillIds = skillIds;
            this.inputs = inputs;
            this.outputs = outputs;
        }

        private Long nodeId() {
            return nodeId;
        }

        private int seq() {
            return seq;
        }

        private String name() {
            return name;
        }

        private String description() {
            return description;
        }

        private String inputType() {
            return inputType;
        }

        private List<Long> skillIds() {
            return skillIds;
        }

        private List<PreparedItem> inputs() {
            return inputs;
        }

        private List<PreparedItem> outputs() {
            return outputs;
        }
    }

    /**
     * 规整后的输入项 / 输出项。
     *
     * @param itemKey     输出项稳定键（输入项为 null）
     * @param name        名称
     * @param description 说明
     * @param fileKind    文件类型
     * @param required    是否必填
     */
    private record PreparedItem(String itemKey, String name, String description, String fileKind,
                                boolean required) {
    }
}
