package com.fde.workbench.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.result.PageResult;
import com.fde.workbench.dao.entity.FileRecord;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.entity.RequirementNode;
import com.fde.workbench.dao.entity.RequirementWithStats;
import com.fde.workbench.dao.entity.Workflow;
import com.fde.workbench.dao.entity.WorkflowNode;
import com.fde.workbench.dao.entity.WorkflowNodeRow;
import com.fde.workbench.dao.entity.WorkflowNodeSkillDetail;
import com.fde.workbench.dao.mapper.FileMapper;
import com.fde.workbench.dao.mapper.NodeInputMapper;
import com.fde.workbench.dao.mapper.NodeOutputMapper;
import com.fde.workbench.dao.mapper.RequirementMapper;
import com.fde.workbench.dao.mapper.SkillMapper;
import com.fde.workbench.dao.mapper.WorkflowMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.bo.PageQuery;
import com.fde.workbench.service.bo.RequirementUpdateCommand;
import com.fde.workbench.service.security.CurrentUserContext;
import com.fde.workbench.service.vo.NodeSnapshots;
import com.fde.workbench.service.vo.RequirementDetailVO;
import com.fde.workbench.service.vo.RequirementVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 需求与节点实例服务（docs/api.md §7，PRD REQ-F1~F3 / AC-F1~F3、REQ-X1/X2 的需求侧）。
 * <p>
 * <b>职责边界</b>：本类负责"需求"这一层——增删改查，以及创建需求时<b>在同一事务内</b>把流程节点
 * 快照成节点实例。会话登记、节点信息页、上传与推进属于 T5-1，本类不创建会话（PRD AC-F1.1 之后
 * 的工作流）。涉及会话表与文件表的只有"物理删除需求时必须按外键顺序连带清理"，见 {@link #delete}。
 * <p>
 * <b>权限判定（统一走 {@link ProjectAccessService}，不另写一套）</b>：
 * <ul>
 *   <li>列表 / 创建 / 详情：项目成员即可（M），否则 403/10201；</li>
 *   <li>编辑 / 删除：需求创建人 或 项目 owner（C），否则 403/10202；</li>
 *   <li>{@code permissions.canOperate} = （创建人 或 owner）<b>且</b> status=in_progress。</li>
 * </ul>
 * <p>
 * <b>revision</b>：需求表自带 {@code revision INT}，编辑与删除都用"WHERE revision = 期望值"
 * 比较并交换；编辑 +1，删除不 +1（行已不存在）。缺失 revision → 400/10001，不匹配 → 409/10402。
 * <p>
 * <b>快照语义（核心）</b>：创建时把流程节点的名称、说明、输入类型、输入项、输出项、技能（含
 * 版本号）整体复制进 {@code wb_requirement_node}；之后修改流程模板<b>只影响新建需求</b>，
 * 已有需求永远读自己的快照（PRD AC-F2.2）。
 * <p>
 * <b>workspace 路径</b>：{@code ${fde.workbench.storage-root}/ws/<requirementId>} 的绝对路径。
 * 由于路径里含自增 ID，而 ID 只有插入后才能拿到：先以空字符串占位插入拿到 ID，再在同一事务内
 * {@code UPDATE} 回填绝对路径并创建目录；**目录创建失败或路径撞车都抛异常，让整个事务回滚**
 * （不会留下"有需求行却没有 workspace"的记录）。
 */
@Service
public class RequirementService {

    private static final Logger log = LoggerFactory.getLogger(RequirementService.class);

    /** 需求标题最大长度（与 wb_requirement.title VARCHAR(200) 一致） */
    private static final int MAX_TITLE_LENGTH = 200;

    /** 需求说明最大长度（TEXT 列，这里只做一个防御上限，避免误传超大文本） */
    private static final int MAX_DESCRIPTION_LENGTH = 10000;

    /** 合法的需求状态过滤值（列表 {@code ?status=}） */
    private static final Set<String> STATUSES = Set.of(Requirement.STATUS_IN_PROGRESS,
            Requirement.STATUS_COMPLETED);

    /**
     * 按可预测工作区路径创建目录时的重试次数。
     * <p>
     * MySQL 自增 ID 在事务回滚后<b>不会回退</b>，下一次插入拿到的是新 ID，因此"目录已被占用"这一
     * 冲突正常重试一次就能通过；给 3 次足以覆盖极端的并发与连续回滚，再多就是掩盖真正的问题。
     */
    private static final int WORKSPACE_ATTEMPTS = 3;

    /** 工作区绝对路径长度上限（与 wb_requirement.dsh_workspace_path VARCHAR(1000) 一致） */
    private static final int MAX_WORKSPACE_PATH_LENGTH = 1000;

    /** 路径冲突探测时回看的需求条数 */
    private static final int WORKSPACE_PROBE_LIMIT = 10;

    private final RequirementMapper requirementMapper;

    private final WorkflowMapper workflowMapper;

    private final SkillMapper skillMapper;

    private final ProjectAccessService projectAccessService;

    private final CurrentUserContext currentUserContext;

    private final WorkbenchStorage storage;

    /** T5-1：删除需求时清理节点输入 / 输出与文件登记 */
    private final NodeInputMapper nodeInputMapper;

    private final NodeOutputMapper nodeOutputMapper;

    private final FileMapper fileMapper;

    private final TransactionTemplate transactionTemplate;

    /** 快照 JSON 的序列化与解析（结构见 {@link NodeSnapshots}） */
    private final ObjectMapper objectMapper;

    public RequirementService(RequirementMapper requirementMapper,
                              WorkflowMapper workflowMapper,
                              SkillMapper skillMapper,
                              ProjectAccessService projectAccessService,
                              CurrentUserContext currentUserContext,
                              WorkbenchStorage storage,
                              NodeInputMapper nodeInputMapper,
                              NodeOutputMapper nodeOutputMapper,
                              FileMapper fileMapper,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper) {
        this.requirementMapper = requirementMapper;
        this.workflowMapper = workflowMapper;
        this.skillMapper = skillMapper;
        this.projectAccessService = projectAccessService;
        this.currentUserContext = currentUserContext;
        this.storage = storage;
        this.nodeInputMapper = nodeInputMapper;
        this.nodeOutputMapper = nodeOutputMapper;
        this.fileMapper = fileMapper;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 项目需求列表（成员即可）：支持标题模糊 {@code keyword} 与 {@code status} 过滤，按创建时间倒序。
     *
     * @param projectId 项目 ID
     * @param keyword   标题模糊词，可空
     * @param status    状态过滤（in_progress / completed），可空
     * @param page      分页参数
     * @return 分页结果
     * @throws BizException 项目不存在 404/10301；非成员 403/10201；状态非法 400/10001
     */
    public PageResult<RequirementVO> list(Long projectId, String keyword, String status, PageQuery page) {
        CurrentUser user = currentUserContext.require();
        ProjectWithStats project = projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, user.userId());

        String kw = (keyword == null || keyword.isBlank()) ? null : escapeLike(keyword.trim());
        String statusFilter = normalizeStatusFilter(status);
        long total = requirementMapper.countByProject(projectId, kw, statusFilter);
        List<RequirementWithStats> rows = total == 0
                ? List.of()
                : requirementMapper.selectByProject(projectId, kw, statusFilter, page.offset(), page.size());
        // 列表里每一行都要判断"我能不能编辑"，owner 判定只需算一次（双源校验内部会查项目与成员表）
        boolean owner = projectAccessService.isOwner(project, user.userId());
        List<RequirementVO> items = rows.stream()
                .map(row -> RequirementVO.of(row, isCreatorOrOwner(row, user.userId(), owner)))
                .toList();
        return PageResult.of(items, total, page.page(), page.size());
    }

    // ------------------------------------------------------------------
    // 创建（核心：一个事务）
    // ------------------------------------------------------------------

    /**
     * 创建需求并生成节点实例（docs/api.md §7 POST、docs/architecture.md §5.1）。
     * <p>
     * 事务内顺序（任一步失败整体回滚，<b>不留下半个流程实例</b>）：
     * <ol>
     *   <li>插入 {@code wb_requirement}：{@code current_seq=1}、{@code status='in_progress'}、
     *       {@code revision=1}，{@code dsh_workspace_path} 先写空串占位；</li>
     *   <li>回填 workspace 绝对路径 {@code <storage-root>/ws/<id>} 并创建目录
     *       （路径冲突 → 抛异常回滚 → 外层换新 ID 重试，见 {@link #createOnce}）；</li>
     *   <li>按流程节点 {@code seq} 顺序批量插入 {@code wb_requirement_node}，写入快照；</li>
     *   <li>第 1 个节点 {@code status='current'} 且写 {@code started_at}，其余 {@code waiting}。</li>
     * </ol>
     * <b>不创建会话</b>：会话在首次进入节点时由 T5-1 登记（PRD REQ-X2 的绑定发生在"新建会话"时）。
     * <p>
     * <b>workspace 路径的做法</b>：{@code ws/<requirementId>} 里的 ID 只有插入后才有，因此先用空串
     * 占位插入、拿到自增 ID 后在同一事务内 {@code UPDATE} 回填绝对路径并创建目录；若目录名已被
     * 另一个需求占用（自增 ID 复用导致），抛异常回滚并重试，绝不让两个需求共用一个 workspace。
     * <p>
     * 校验（只读，放在事务外）：项目存在 → 我是成员 → 标题/说明合法 → 流程属于本项目且未删除
     * → 流程至少有 1 个节点。技能快照同样在事务外一次取全，避免长事务里逐条查库。
     *
     * @param projectId  项目 ID
     * @param title      标题（1–200 字符）
     * @param description 说明，可空
     * @param workflowId 流程 ID（必填）
     * @return 需求详情（与 GET 详情结构一致）
     * @throws BizException 404/10301、403/10201、400/10001
     */
    public RequirementDetailVO create(Long projectId, String title, String description, Long workflowId) {
        CurrentUser user = currentUserContext.require();
        projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, user.userId());

        String trimmedTitle = requireText(title, MAX_TITLE_LENGTH,
                "需求标题不能为空", "需求标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        String desc = normalizeOptionalText(description, MAX_DESCRIPTION_LENGTH,
                "需求说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        Workflow workflow = requireUsableWorkflow(projectId, workflowId);

        // 事务外一次性把"模板 → 快照"算好：只读，失败时没有任何写入需要回滚
        List<PreparedNode> preparedNodes = prepareNodes(workflow.getId());

        Long requirementId = createWithWorkspace(projectId, workflow.getId(), trimmedTitle, desc,
                user.userId(), preparedNodes);
        log.info("创建需求：projectId={}, workflowId={}, requirementId={}, 节点数={}, 操作人={}",
                projectId, workflow.getId(), requirementId, preparedNodes.size(), user.userId());

        // 重读一次再返回：revision、时间、节点 ID 都以库为准，不拿内存里的值凑
        return detail(requirementId);
    }

    /**
     * 带"工作区路径冲突重试"的创建。
     * <p>
     * 为什么需要重试：工作区目录名是需求 ID，而 ID 只有插入后才知道。若 {@code ws/<id>} 目录已存在，
     * 它<b>通常属于另一个需求</b>（自增 ID 在回滚后被复用），此时若照常提交，两个需求就共用一个
     * workspace，会话与文件互相可见——违反 PRD REQ-X1 的需求级隔离。这里的处理是：
     * 抛异常让事务回滚（连需求行一起撤掉）→ 重新插入拿到新的自增 ID → 最多重试
     * {@link #WORKSPACE_ATTEMPTS} 次。即使极端情况下连续撞车，也只会返回 409 而不会共享目录。
     *
     * @param projectId    项目 ID
     * @param workflowId   流程 ID
     * @param title        标题
     * @param description  说明
     * @param creatorId    创建人
     * @param preparedNodes 快照后的节点（不含 requirementId，事务内回填）
     * @return 新需求 ID
     * @throws BizException 409/10404（连续撞车）、502/19902（目录创建失败）
     */
    private Long createWithWorkspace(Long projectId, Long workflowId, String title, String description,
                                     Long creatorId, List<PreparedNode> preparedNodes) {
        for (int attempt = 1; attempt <= WORKSPACE_ATTEMPTS; attempt++) {
            try {
                Long id = transactionTemplate.execute(status ->
                        createOnce(projectId, workflowId, title, description, creatorId, preparedNodes));
                return Objects.requireNonNull(id, "创建需求事务未返回 ID");
            } catch (WorkspacePathConflictException ex) {
                log.warn("需求 workspace 路径冲突，回滚后重试（第 {}/{} 次）：requirementId={}, path={}",
                        attempt, WORKSPACE_ATTEMPTS, ex.requirementId, ex.path);
                if (attempt == WORKSPACE_ATTEMPTS) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "需求工作区目录已被占用，请稍后重试");
                }
            }
        }
        throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "需求工作区目录已被占用，请稍后重试");
    }

    /**
     * 单次创建事务（见 {@link #create} 的四步顺序）。
     *
     * @param projectId     项目 ID
     * @param workflowId    流程 ID
     * @param title         标题
     * @param description   说明
     * @param creatorId     创建人
     * @param preparedNodes 快照后的节点
     * @return 新需求 ID
     */
    private Long createOnce(Long projectId, Long workflowId, String title, String description,
                            Long creatorId, List<PreparedNode> preparedNodes) {
        LocalDateTime now = now();

        // ① 插入需求行：workspace 路径先占位（列 NOT NULL，而路径要等自增 ID）
        Requirement requirement = new Requirement();
        requirement.setProjectId(projectId);
        requirement.setWorkflowId(workflowId);
        requirement.setTitle(title);
        requirement.setDescription(description);
        requirement.setCreatorId(creatorId);
        requirement.setStatus(Requirement.STATUS_IN_PROGRESS);
        requirement.setCurrentSeq(1);
        requirement.setDshWorkspacePath("");
        requirement.setRevision(1);
        requirement.setCreatedAt(now);
        requirement.setUpdatedAt(now);
        requirementMapper.insert(requirement);
        Long requirementId = requirement.getId();

        // ② 回填绝对路径 + 创建目录：失败抛异常 → 整个事务回滚，"需求行"不会留下
        WorkbenchStorage.WorkspaceCreation workspace = storage.createRequirementWorkspace(requirementId);
        if (!workspace.created() && workspacePathOwnedByOtherWorkflow(workflowId, workspace.absolutePath())) {
            // 目录先于本次插入就存在，且最近的记录显示它属于另一个流程的需求 → 视为 ID 复用造成的撞车
            throw new WorkspacePathConflictException(requirementId, workspace.absolutePath());
        }
        if (workspace.absolutePath().length() > MAX_WORKSPACE_PATH_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "存储根路径过长，需求工作区绝对路径超过 " + MAX_WORKSPACE_PATH_LENGTH + " 个字符");
        }
        requirementMapper.updateWorkspacePath(requirementId, workspace.absolutePath(), now);

        // ③ 节点实例：按流程 seq 顺序批量插入，快照已算好
        List<RequirementNode> nodes = new ArrayList<>(preparedNodes.size());
        for (PreparedNode prepared : preparedNodes) {
            RequirementNode node = new RequirementNode();
            node.setRequirementId(requirementId);
            node.setWorkflowNodeId(prepared.workflowNodeId());
            node.setSeq(prepared.seq());
            node.setName(prepared.name());
            node.setDescription(prepared.description());
            node.setInputType(prepared.inputType());
            node.setInputsSnapshot(prepared.inputsJson());
            node.setOutputsSnapshot(prepared.outputsJson());
            node.setSkillsSnapshot(prepared.skillsJson());
            // ④ 第一个节点即当前节点（current_seq=1），写 started_at；其余 waiting
            if (prepared.seq() == 1) {
                node.setStatus(RequirementNode.STATUS_CURRENT);
                node.setStartedAt(now);
            } else {
                node.setStatus(RequirementNode.STATUS_WAITING);
            }
            node.setCreatedAt(now);
            nodes.add(node);
        }
        requirementMapper.insertNodes(nodes);
        return requirementId;
    }

    /**
     * 判断"创建前就已存在的 workspace 目录"是否属于<b>另一个需求</b>。
     * <p>
     * 判定口径：本流程最近 {@link #WORKSPACE_PROBE_LIMIT} 条需求的工作区路径里，有任意一条与本次
     * 路径相同 → 说明该目录是别人（另一个需求）留下的，本次不能共用。
     * 为什么查"本流程"而不是全表：命中路径的另一个需求已经不在本流程下时（理论上不可能，
     * 因为 workspace 路径只在创建时写一次且与需求一一对应），全表探测的代价远大于收益；
     * 现实中的撞车只来自"回滚复用了附近的自增 ID"，而那条需求的流程正是本流程。
     * 未命中时按"历史残留目录"处理直接复用（语义与"目录已存在"等价，不丢数据）。
     *
     * @param workflowId 流程 ID
     * @param path       本次要用的绝对路径
     * @return true 表示该路径已被另一个需求占用
     */
    private boolean workspacePathOwnedByOtherWorkflow(Long workflowId, String path) {
        return workflowMapper.selectRecentWorkspacePaths(workflowId, WORKSPACE_PROBE_LIMIT).stream()
                .anyMatch(path::equals);
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    /**
     * 需求详情（成员即可）：{@code { requirement, nodes[], permissions }}。
     *
     * @param requirementId 需求 ID
     * @return 需求详情
     * @throws BizException 需求不存在 404/10301；项目不存在 404/10301；非成员 403/10201
     */
    public RequirementDetailVO detail(Long requirementId) {
        CurrentUser user = currentUserContext.require();
        RequirementWithStats requirement = requireExistingRequirement(requirementId);
        ProjectWithStats project = projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());

        boolean owner = projectAccessService.isOwner(project, user.userId());
        boolean isCreator = Objects.equals(requirement.getCreatorId(), user.userId());
        boolean creatorOrOwner = isCreator || owner;
        // canOperate：权限 C 之外再加"需求仍在进行中"——已完成的需求对任何人都是只读（api.md §7）
        boolean canOperate = creatorOrOwner
                && Requirement.STATUS_IN_PROGRESS.equals(requirement.getStatus());

        List<RequirementDetailVO.NodeProgressVO> nodes =
                requirementMapper.selectNodesByRequirement(requirementId).stream()
                        .map(RequirementDetailVO.NodeProgressVO::of)
                        .toList();
        return new RequirementDetailVO(
                RequirementVO.of(requirement, creatorOrOwner),
                nodes,
                new RequirementDetailVO.PermissionsVO(canOperate, isCreator));
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    /**
     * 编辑需求标题 / 说明（创建人或 owner，带 revision 乐观锁）。
     * <p>
     * 不允许更换流程：api.md §7 的 PUT 只收标题与说明，PRD AC-F2.1"创建后流程不可更换"
     * 由接口形状本身保证（请求体里根本没有 workflowId）。
     *
     * @param requirementId 需求 ID
     * @param command       {@code {title, description, revision}}
     * @return 更新后的需求详情
     * @throws BizException 404/10301、403/10201、403/10202、400/10001、409/10402
     */
    public RequirementDetailVO update(Long requirementId, RequirementUpdateCommand command) {
        RequirementWithStats requirement = requireOperableRequirement(requirementId);
        if (command == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        String title = requireText(command.title(), MAX_TITLE_LENGTH,
                "需求标题不能为空", "需求标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        String desc = normalizeOptionalText(command.description(), MAX_DESCRIPTION_LENGTH,
                "需求说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        int expected = requireRevision(command.revision());

        int affected = requirementMapper.updateTitleAndDescription(requirementId, title, desc, expected, now());
        if (affected == 0) {
            throw revisionConflict(requirementId, expected);
        }
        log.info("编辑需求：requirementId={}, 标题={}", requirementId, title);
        return detail(requirementId);
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /**
     * 物理删除需求（创建人或 owner，带 revision 前置一致性校验）。
     * <p>
     * 需求表没有 {@code deleted} 列，且全部子表外键都是 RESTRICT，因此必须在一个事务内按依赖顺序
     * 显式清理（PRD AC-F1.3：需求及其节点实例、会话对应关系一并移除）。顺序：
     * <ol>
     *   <li>{@code wb_requirement_node.primary_session_id = NULL}（解除节点 → 会话外键）；</li>
     *   <li>{@code wb_session_runtime_info}（同时引用会话与节点）；</li>
     *   <li>{@code wb_node_session_link}（节点 × 会话弱关联）；</li>
     *   <li><b>{@code wb_requirement_node_input} / {@code wb_requirement_node_output}</b>
     *       （T5-1 写入的节点输入与输出，外键指向节点与 {@code wb_file}）；</li>
     *   <li>{@code wb_requirement_node}；</li>
     *   <li><b>{@code wb_file}</b>（节点输入 / 输出的文件登记；必须在 4 与 5 之后、需求行之前删，
     *       因为 {@code fk_node_input_file / fk_node_output_file} 与 {@code fk_file_requirement} 都是 RESTRICT）；</li>
     *   <li>{@code wb_agent_session}；</li>
     *   <li>最后 {@code DELETE FROM wb_requirement WHERE id = ? AND revision = ?}：影响 0 行说明
     *       revision 已过期（或需求已被他人删除），抛 409 让整个事务回滚，前面删掉的东西全部恢复。</li>
     * </ol>
     * 删需求行必须放在最后：它是所有子表的父行，外键 RESTRICT 会挡住"先删父后删子"。
     * <p>
     * <b>T5-1 补齐的缺口</b>：本方法原先只清理节点与会话，若需求下已有上传的输入或已提交的输出，
     * 删节点会被 {@code fk_node_input_node / fk_node_output_node} 挡住并返回 500（事务整体回滚，
     * 不会删一半）。现在在第 4 / 6 步补上三张表，删除需求才能真正删干净；
     * 对应测试见 {@code NodeApiTest#deleteRequirement_cleansInputsOutputsFilesAndSessions}。
     * <p>
     * 磁盘文件在<b>事务提交之后</b>删除：先记下 {@code wb_file.storage_rel_path}，提交成功后逐个删
     * （失败只记日志），避免"目录已删、事务回滚"造成数据与文件不一致；最后尝试删除 workspace 目录
     * （目录非空则保留，见 {@link WorkbenchStorage#deleteRequirementWorkspaceIfEmpty}）。
     *
     * @param requirementId 需求 ID
     * @param revision      前端持有的 revision（必填）
     * @throws BizException 404/10301、403/10201、403/10202、400/10001、409/10402
     */
    public void delete(Long requirementId, RevisionStamp revision) {
        RequirementWithStats requirement = requireOperableRequirement(requirementId);
        int expected = requireRevision(revision);

        // 事务前先记下磁盘路径：事务里删完就查不到了（只读操作，失败无副作用）
        List<String> filePaths = fileMapper.selectByRequirement(requirementId).stream()
                .map(FileRecord::getStorageRelPath)
                .filter(path -> path != null && !path.isBlank())
                .toList();

        int[] removed = transactionTemplate.execute(status -> {
            // 顺序见方法注释：先解除跨表引用，再删节点与文件，最后删需求行（revision 闸门）
            requirementMapper.clearPrimarySessionByRequirement(requirementId);
            requirementMapper.deleteSessionRuntimeInfoByRequirement(requirementId);
            requirementMapper.deleteNodeSessionLinksByRequirement(requirementId);
            // T5-1：节点输入 / 输出（引用节点与 wb_file，必须在删节点、删文件之前）
            nodeInputMapper.deleteByRequirement(requirementId);
            nodeOutputMapper.deleteByRequirement(requirementId);
            requirementMapper.deleteNodesByRequirement(requirementId);
            // T5-1：文件登记（节点输入 / 输出；技能包文件 requirement_id 为 NULL，不受影响）
            int fileCount = fileMapper.deleteByRequirement(requirementId);
            requirementMapper.deleteSessionsByRequirement(requirementId);
            int deleted = requirementMapper.deleteById(requirementId, expected);
            if (deleted != 1) {
                // 走到这里说明 revision 在本次请求期间被别人改过：抛异常 → 上面所有删除回滚
                throw revisionConflict(requirementId, expected);
            }
            return new int[]{deleted, fileCount};
        });
        log.info("删除需求（物理删除）：requirementId={}, 需求行={}, 文件登记={}", requirementId,
                removed == null ? 0 : removed[0], removed == null ? 0 : removed[1]);

        // 事务已提交：再删磁盘文件（失败只记日志，不影响删除结果）
        filePaths.forEach(this::deleteStoredFileQuietly);
        boolean removedDir = storage.deleteRequirementWorkspaceIfEmpty(requirementId);
        if (!removedDir) {
            log.info("需求 workspace 目录非空，按留存策略保留：requirementId={}", requirementId);
        }
    }

    /**
     * 删除需求时清理单个存储文件（路径经存储根校验，失败只记日志）。
     * <p>
     * 为什么不整目录递归删：模型可能在 workspace 里留下大量文件，递归删除是危险操作；
     * 这里只删"数据库登记过的文件"，其余残留按留存策略保留（与
     * {@link WorkbenchStorage#deleteRequirementWorkspaceIfEmpty} 的口径一致）。
     *
     * @param storageRelPath 相对存储根的路径
     */
    private void deleteStoredFileQuietly(String storageRelPath) {
        try {
            Path path = storage.resolveWithin(storage.root(), storageRelPath);
            Files.deleteIfExists(path);
        } catch (IOException | BizException ex) {
            log.warn("删除需求时清理文件失败（按留存策略保留）：{}", storageRelPath);
        }
    }

    // ------------------------------------------------------------------
    // 快照组装（事务外，只读）
    // ------------------------------------------------------------------

    /**
     * 校验流程可用于创建需求：属于本项目、未逻辑删除、至少 1 个节点。
     *
     * @param projectId  项目 ID
     * @param workflowId 流程 ID
     * @return 流程实体
     * @throws BizException 400/10001（缺 workflowId）、404/10301、400/10001（非本项目 / 无节点）
     */
    private Workflow requireUsableWorkflow(Long projectId, Long workflowId) {
        if (workflowId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "创建需求必须选择一个流程（workflowId）");
        }
        // selectById 已过滤 deleted=0：流程不存在或已删除都按"不存在"处理
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null || !projectId.equals(workflow.getProjectId())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "流程不存在或不属于该项目");
        }
        return workflow;
    }

    /**
     * 把流程节点展开成"待写入的节点实例"（含三个 JSON 快照串）。
     * <p>
     * 数据来源全部是流程模板的<b>当场读取</b>，一次 LEFT JOIN 取回节点 + 输入项 + 输出项，
     * 再单独取一次"节点技能 + 技能当前版本"。两者都在事务外完成，事务里只做纯写入。
     *
     * @param workflowId 流程 ID
     * @return 按 seq 升序的节点快照
     * @throws BizException 流程没有节点 → 400/10001
     */
    private List<PreparedNode> prepareNodes(Long workflowId) {
        List<WorkflowNodeRow> rows = workflowMapper.selectNodeDetails(workflowId);
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "该流程还没有节点，不能创建需求");
        }
        // 节点技能：nodeId → 技能快照列表（SQL 已按节点 seq、技能 seq 排序）
        Map<Long, List<NodeSnapshots.SkillItem>> skillsByNode = new LinkedHashMap<>();
        for (WorkflowNodeSkillDetail detail : workflowMapper.selectNodeSkillsDetail(workflowId)) {
            skillsByNode.computeIfAbsent(detail.getNodeId(), ignored -> new ArrayList<>())
                    .add(new NodeSnapshots.SkillItem(detail.getSkillId(), detail.getSkillName(),
                            detail.getCurrentVersionId(), detail.getCurrentVersionNo()));
        }

        // 展平行按节点归并，输入项 / 输出项各自按 itemId 去重（笛卡尔积）
        Map<Long, NodeAccumulator> accumulators = new LinkedHashMap<>();
        for (WorkflowNodeRow row : rows) {
            NodeAccumulator acc = accumulators.computeIfAbsent(row.getNodeId(),
                    ignored -> new NodeAccumulator(row));
            acc.add(row);
        }

        List<PreparedNode> prepared = new ArrayList<>(accumulators.size());
        for (NodeAccumulator acc : accumulators.values()) {
            List<NodeSnapshots.SkillItem> skills = skillsByNode.getOrDefault(acc.nodeId, List.of());
            // free 节点：即便模板上残留了固定输入项，也写成空数组——free 的语义是"整节点上传"，
            // 线上读取方（T5-1）按 snapshot 判定，残留项会让页面错误地显示逐项上传入口
            List<NodeSnapshots.InputItem> inputs = WorkflowNode.INPUT_TYPE_FIXED.equals(acc.inputType)
                    ? List.copyOf(acc.inputs.values())
                    : List.of();
            NodeSnapshots.NodeSnapshot snapshot = new NodeSnapshots.NodeSnapshot(inputs,
                    List.copyOf(acc.outputs.values()), List.copyOf(skills));
            prepared.add(new PreparedNode(acc.nodeId, acc.seq, acc.name, acc.description, acc.inputType,
                    toJson(snapshot.inputs()), toJson(snapshot.outputs()), toJson(snapshot.skills())));
        }
        return prepared;
    }

    /**
     * 节点展平行归并器：按节点收集输入项 / 输出项（各自按 itemId 去重，保持 SQL 给出的顺序）。
     */
    private static final class NodeAccumulator {

        private final Long nodeId;
        private final Integer seq;
        private final String name;
        private final String description;
        private final String inputType;
        private final Map<Long, NodeSnapshots.InputItem> inputs = new LinkedHashMap<>();
        private final Map<Long, NodeSnapshots.OutputItem> outputs = new LinkedHashMap<>();

        private NodeAccumulator(WorkflowNodeRow row) {
            this.nodeId = row.getNodeId();
            this.seq = row.getNodeSeq();
            this.name = row.getNodeName();
            this.description = row.getNodeDescription();
            this.inputType = row.getNodeInputType();
        }

        private void add(WorkflowNodeRow row) {
            if (row.getInputItemId() != null) {
                inputs.putIfAbsent(row.getInputItemId(),
                        new NodeSnapshots.InputItem(row.getInputItemId(), row.getInputName(),
                                row.getInputFileKind(), Boolean.TRUE.equals(row.getInputRequired())));
            }
            if (row.getOutputItemId() != null) {
                outputs.putIfAbsent(row.getOutputItemId(),
                        new NodeSnapshots.OutputItem(row.getOutputItemId(), row.getOutputItemKey(),
                                row.getOutputName(), row.getOutputFileKind(),
                                Boolean.TRUE.equals(row.getOutputRequired())));
            }
        }
    }

    /**
     * 节点快照序列化为 JSON 字符串（MySQL JSON 列会原样保存键的顺序与 null 值）。
     *
     * @param value 快照列表
     * @return JSON 字符串
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            // 快照都是纯 record（字符串 / 数字 / 布尔），序列化失败属于程序错误而非用户输入问题
            throw new IllegalStateException("节点快照序列化失败", ex);
        }
    }

    // ------------------------------------------------------------------
    // 公共校验 / 工具
    // ------------------------------------------------------------------

    /**
     * 取需求并判定"可编辑 / 可删除"（权限 C：创建人或 owner）。
     *
     * @param requirementId 需求 ID
     * @return 需求（含统计列）
     * @throws BizException 需求不存在 404/10301；项目不存在 404/10301；非成员 403/10201；
     *                      非创建者且非 owner 403/10202
     */
    private RequirementWithStats requireOperableRequirement(Long requirementId) {
        CurrentUser user = currentUserContext.require();
        RequirementWithStats requirement = requireExistingRequirement(requirementId);
        ProjectWithStats project = projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());
        if (!isCreatorOrOwner(requirement, user.userId(), projectAccessService.isOwner(project, user.userId()))) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有需求创建者或项目 owner 可以修改该需求");
        }
        return requirement;
    }

    /**
     * 需求是否存在。
     *
     * @param requirementId 需求 ID
     * @return 需求（含统计列）
     * @throws BizException 404/10301
     */
    private RequirementWithStats requireExistingRequirement(Long requirementId) {
        RequirementWithStats requirement =
                requirementId == null ? null : requirementMapper.selectById(requirementId);
        if (requirement == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "需求不存在");
        }
        return requirement;
    }

    /**
     * 权限 C 判定：需求创建人 或 项目 owner。
     *
     * @param requirement 需求
     * @param userId      当前用户
     * @param owner       当前用户是否为本项目 owner（由调用方用 {@link ProjectAccessService#isOwner} 算好）
     * @return true 表示可编辑 / 删除
     */
    private static boolean isCreatorOrOwner(Requirement requirement, Long userId, boolean owner) {
        return owner || Objects.equals(requirement.getCreatorId(), userId);
    }

    /**
     * 解析请求里的 revision（需求表自带 INT 列，直接取数值）。
     *
     * @param revision 请求中的 revision
     * @return 期望的 revision
     * @throws BizException 400/10001（缺失或不是正整数）
     */
    private static int requireRevision(RevisionStamp revision) {
        if (revision == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "缺少 revision（请先获取需求详情，再带回其 revision）");
        }
        long value = revision.epochMilli();
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "revision 不合法：" + value);
        }
        return (int) value;
    }

    /**
     * revision 冲突：先确认需求还在（不在就是 404），否则 409/10402。
     *
     * @param requirementId 需求 ID
     * @param expected      期望的 revision
     * @return 抛出用的异常
     */
    private BizException revisionConflict(Long requirementId, int expected) {
        RequirementWithStats current = requirementMapper.selectById(requirementId);
        if (current == null) {
            return BizException.of(ErrorCode.NOT_FOUND, "需求不存在");
        }
        log.info("需求 revision 冲突：requirementId={}, 期望={}，实际={}", requirementId, expected,
                current.getRevision());
        return BizException.of(ErrorCode.REVISION_CONFLICT, "需求已被他人修改，请刷新后重试");
    }

    /**
     * 列表的状态过滤参数规整。
     *
     * @param status 原始参数
     * @return 规整后的状态；空串/null 表示不过滤
     * @throws BizException 400/10001（取值不在 in_progress / completed 之内）
     */
    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String trimmed = status.trim();
        if (!STATUSES.contains(trimmed)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "status 只能是 in_progress 或 completed");
        }
        return trimmed;
    }

    /**
     * 转义 LIKE 通配符（{@code \ % _}）。
     * <p>
     * 与 XML 里 {@code ESCAPE '\\'} 配套：不转义的话用户搜索 {@code 100%} 会变成"任意结尾"，
     * 既搜不准也可能让标题很长的项目全表扫描。
     *
     * @param keyword 原始关键字
     * @return 转义后的关键字
     */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 当前业务时间（截断到毫秒，与 DATETIME(3) 精度一致）。
     *
     * @return 当前时间
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
     * 可空文本规整：空白 → null；超长直接拒绝。
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
     * 事务内待写入的节点快照（JSON 已序列化）。
     *
     * @param workflowNodeId 来源流程节点 ID
     * @param seq            执行顺序（= 流程节点 seq）
     * @param name           快照名称
     * @param description    快照说明
     * @param inputType      快照输入类型
     * @param inputsJson     输入项快照 JSON
     * @param outputsJson    输出项快照 JSON
     * @param skillsJson     技能快照 JSON
     */
    private record PreparedNode(Long workflowNodeId, Integer seq, String name, String description,
                                String inputType, String inputsJson, String outputsJson, String skillsJson) {
    }

    /**
     * 内部异常：workspace 路径已被另一个需求占用。
     * <p>
     * 不用 {@link BizException}：它要触发"回滚后换新 ID 重试"而不是直接把 409 返回给用户，
     * 由 {@link #createWithWorkspace} 捕获；重试耗尽后才转成对外的 409/10404。
     */
    private static final class WorkspacePathConflictException extends RuntimeException {

        private final Long requirementId;
        private final String path;

        private WorkspacePathConflictException(Long requirementId, String path) {
            super("需求 workspace 路径已被占用：" + path);
            this.requirementId = requirementId;
            this.path = path;
        }
    }
}
