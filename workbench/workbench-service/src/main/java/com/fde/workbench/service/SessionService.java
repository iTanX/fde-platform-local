package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.AgentSession;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.RequirementNode;
import com.fde.workbench.dao.entity.RequirementWithStats;
import com.fde.workbench.dao.mapper.RequirementMapper;
import com.fde.workbench.dao.mapper.SessionMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.bo.SessionRegisterCommand;
import com.fde.workbench.service.security.CurrentUserContext;
import com.fde.workbench.service.vo.SessionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 会话服务（docs/api.md §9、§11 {@code /internal/sessions}；PRD REQ-X1/X2、AC-F7.2/F7.4/X2.1/X2.2）。
 * <p>
 * <b>职责边界</b>：本类只管"会话与需求 / 节点的图书链"——登记、列表、touch、默认会话解析。
 * DSH 会话本身由插件创建（{@code uiWorkspace.connectWorkspace}），Java 侧不做任何 DSH 调用。
 * <p>
 * <b>登记事务（api.md §9 四步，缺一不可）</b>：
 * <ol>
 *   <li>插入 {@code wb_agent_session}（{@code dsh_session_id} 全局唯一）；</li>
 *   <li>插入 {@code wb_session_runtime_info}（记录最后所在节点，恢复时用）；</li>
 *   <li><b>若节点 {@code primary_session_id} 为空</b>则设为新会话（AC-X2.1：已绑定过的节点不被新会话抢走）；</li>
 *   <li>插入 {@code wb_node_session_link}（展示用弱关联）。</li>
 * </ol>
 * 四步在同一个 {@link TransactionTemplate} 里，失败整体回滚——不会出现"会话建了、节点没绑定"的半状态。
 * <p>
 * <b>默认会话解析（api.md §9）</b>：节点 {@code primary_session_id} → 需求下 {@code archived=0}
 * 且 {@code last_active_at} 最大 → 都没有返回 null（前端进入"新建会话"）。
 * 节点推进时<b>不复制</b>旧节点绑定（plugin-integration.md §6）：下一节点没有 primary 时会落到
 * 第 2 条，自然命中同一个会话，历史连续可见（AC-F7.3）。
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** {@code wb_agent_session.dsh_session_id} 长度上限 */
    private static final int MAX_DSH_SESSION_ID_LENGTH = 128;

    /** {@code wb_agent_session.title} 长度上限 */
    private static final int MAX_TITLE_LENGTH = 200;

    private final SessionMapper sessionMapper;

    private final RequirementMapper requirementMapper;

    private final ProjectAccessService projectAccessService;

    private final CurrentUserContext currentUserContext;

    private final TransactionTemplate transactionTemplate;

    public SessionService(SessionMapper sessionMapper,
                          RequirementMapper requirementMapper,
                          ProjectAccessService projectAccessService,
                          CurrentUserContext currentUserContext,
                          TransactionTemplate transactionTemplate) {
        this.sessionMapper = sessionMapper;
        this.requirementMapper = requirementMapper;
        this.projectAccessService = projectAccessService;
        this.currentUserContext = currentUserContext;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 需求下全部会话（项目成员即可），按 {@code lastActiveAt} 倒序（PRD AC-X1.2）。
     *
     * @param requirementId 需求 ID
     * @return 会话列表
     * @throws BizException 需求不存在 404/10301；非成员 403/10201
     */
    public List<SessionVO> list(Long requirementId) {
        CurrentUser user = currentUserContext.require();
        RequirementWithStats requirement = requireExistingRequirement(requirementId);
        projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());

        // "是否是节点默认会话"：需求内任何一个节点绑定了它即为 true（前端用于高亮）
        List<Long> primarySessionIds = requirementMapper.selectNodesByRequirement(requirementId).stream()
                .map(RequirementNode::getPrimarySessionId)
                .filter(Objects::nonNull)
                .toList();
        return sessionMapper.selectByRequirement(requirementId).stream()
                .map(session -> toVO(session, primarySessionIds.contains(session.getId())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 登记（用户侧 + 插件侧）
    // ------------------------------------------------------------------

    /**
     * 用户侧登记会话（权限 C：需求创建人或项目 owner）。
     *
     * @param requirementId 需求 ID
     * @param command       登记命令
     * @return 新会话
     * @throws BizException 需求不存在 404/10301；非成员 403/10201；非创建者非 owner 403/10202；
     *                      参数非法 400/10001；dshSessionId 重复 409/10401；节点不属于该需求 400/10001
     */
    public SessionVO register(Long requirementId, SessionRegisterCommand command) {
        CurrentUser user = currentUserContext.require();
        RequirementWithStats requirement = requireExistingRequirement(requirementId);
        ProjectWithStats project = projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());
        boolean owner = projectAccessService.isOwner(project, user.userId());
        if (!owner && !Objects.equals(requirement.getCreatorId(), user.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有需求创建者或项目 owner 可以登记会话");
        }
        return doRegister(requirement, command, user.userId());
    }

    /**
     * 插件侧登记会话（{@code POST /internal/sessions}，已过 {@code X-Internal-Token} 校验，免 JWT）。
     * <p>
     * <b>操作人从哪里来</b>：内部接口没有用户身份，而 {@code wb_agent_session.created_by} 是
     * {@code NOT NULL} + 外键；插件调用时只能给出需求 ID 与节点 ID，因此这里取需求创建人作为创建者——
     * 会话属于需求本身，需求创建人就是它的责任人，这与"模型在该需求的 workspace 里替创建人干活"的
     * 语义一致（见 {@code InternalOperator}）。
     *
     * @param requirementId 需求 ID
     * @param command       登记命令（{@code nodeId} 为空时取需求当前节点）
     * @return 新会话
     * @throws BizException 需求不存在 404/10301；参数非法 400/10001；重复 409/10401
     */
    public SessionVO registerInternal(Long requirementId, SessionRegisterCommand command) {
        RequirementWithStats requirement = requireExistingRequirement(requirementId);
        return doRegister(requirement, command, requirement.getCreatorId());
    }

    /**
     * 登记事务本体（四步见类注释）。
     *
     * @param requirement 需求
     * @param command     命令
     * @param createdBy   记录到 {@code created_by} 的用户
     * @return 新会话视图
     */
    private SessionVO doRegister(RequirementWithStats requirement, SessionRegisterCommand command, Long createdBy) {
        String dshSessionId = requireDshSessionId(command == null ? null : command.dshSessionId());
        String title = normalizeTitle(command.title());
        RequirementNode node = resolveRegistrationNode(requirement, command.nodeId());

        // 先做一次可读的重复判定（唯一键仍是最终保障，并发时由 DuplicateKeyException 兜住）
        AgentSession existing = sessionMapper.selectByDshSessionId(dshSessionId);
        if (existing != null) {
            throw BizException.of(ErrorCode.DUPLICATE_NAME,
                    "该 DSH 会话已登记过（会话 " + existing.getId() + "）");
        }

        LocalDateTime now = now();
        AgentSession saved;
        try {
            saved = transactionTemplate.execute(status -> {
                AgentSession session = new AgentSession();
                session.setRequirementId(requirement.getId());
                session.setDshSessionId(dshSessionId);
                // workspace 冗余存需求 workspace 绝对路径：恢复会话时不必再回查需求行
                session.setDshWorkspacePath(requirement.getDshWorkspacePath());
                session.setTitle(title);
                session.setCreatedBy(createdBy);
                session.setLastActiveAt(now);
                session.setArchived(0);
                session.setCreatedAt(now);
                session.setUpdatedAt(now);
                sessionMapper.insertSession(session);

                sessionMapper.insertRuntimeInfo(session.getId(), node == null ? null : node.getId(), now);
                if (node != null) {
                    // AC-X2.1 / AC-F7.4：只在 primary 为空时绑定，节点已绑定过就不抢
                    requirementMapper.bindPrimarySessionIfAbsent(node.getId(), session.getId());
                    sessionMapper.insertNodeSessionLink(node.getId(), session.getId(), now);
                }
                return session;
            });
        } catch (DuplicateKeyException ex) {
            // 并发下两次登记同一 dshSessionId：唯一键 uk_agent_session_dsh 拦住后到者
            throw BizException.of(ErrorCode.DUPLICATE_NAME, "该 DSH 会话已登记过：" + dshSessionId);
        }
        log.info("登记会话：requirementId={}, sessionId={}, dshSessionId={}, nodeId={}, createdBy={}",
                requirement.getId(), saved.getId(), dshSessionId, node == null ? null : node.getId(), createdBy);
        return toVO(saved, node != null);
    }

    /**
     * 校验登记时绑定的节点：必须属于该需求。
     *
     * @param requirement 需求
     * @param nodeId      命令里的节点 ID
     * @return 节点；{@code nodeId} 为空时返回 null（不绑定节点）
     * @throws BizException 节点不属于该需求 → 400/10001
     */
    private RequirementNode resolveRegistrationNode(RequirementWithStats requirement, Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        RequirementNode node = requirementMapper.selectNodeById(nodeId);
        if (node == null || !Objects.equals(node.getRequirementId(), requirement.getId())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "节点不属于该需求：" + nodeId);
        }
        return node;
    }

    // ------------------------------------------------------------------
    // touch
    // ------------------------------------------------------------------

    /**
     * 更新会话最后活跃时间（权限 C：需求创建人或 owner）。
     * <p>
     * 不传时间、一律用服务端时间：时间必须由应用写入（api.md §1.5），客户端时钟不可信。
     *
     * @param sessionId 会话 ID
     * @return 更新后的会话
     * @throws BizException 会话不存在 404/10301；项目不存在 404/10301；非成员 403/10201；非创建者非 owner 403/10202
     */
    public SessionVO touch(Long sessionId) {
        CurrentUser user = currentUserContext.require();
        AgentSession session = requireExistingSession(sessionId);
        RequirementWithStats requirement = requireExistingRequirement(session.getRequirementId());
        ProjectWithStats project = projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());
        boolean owner = projectAccessService.isOwner(project, user.userId());
        if (!owner && !Objects.equals(requirement.getCreatorId(), user.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有需求创建者或项目 owner 可以更新会话活跃时间");
        }
        LocalDateTime now = now();
        sessionMapper.updateLastActive(sessionId, now);
        AgentSession updated = sessionMapper.selectById(sessionId);
        List<Long> primarySessionIds = requirementMapper.selectNodesByRequirement(requirement.getId()).stream()
                .map(RequirementNode::getPrimarySessionId)
                .filter(Objects::nonNull)
                .toList();
        return toVO(updated, primarySessionIds.contains(updated.getId()));
    }

    // ------------------------------------------------------------------
    // 默认会话解析
    // ------------------------------------------------------------------

    /**
     * 解析某节点的默认会话（api.md §9 的三步顺序）。
     * <p>
     * 第 1 步命中后<b>不再校验归档</b>：节点绑定是"用户主动在这个节点建了会话"的显式选择
     * （AC-F7.4：新建会话 B 后再打开该节点，默认打开 B），优先级高于"最近活跃"。
     *
     * @param requirementId 需求 ID
     * @param node          节点实例（可空：为空时只看需求级最近活跃）
     * @return 会话；解析不到返回 null
     */
    public AgentSession resolveDefaultSession(Long requirementId, RequirementNode node) {
        if (node != null && node.getPrimarySessionId() != null) {
            AgentSession primary = sessionMapper.selectById(node.getPrimarySessionId());
            if (primary != null && Objects.equals(primary.getRequirementId(), requirementId)) {
                return primary;
            }
        }
        return sessionMapper.selectMostActiveByRequirement(requirementId);
    }

    /**
     * 解析默认会话的 DSH 标识（节点信息页 {@code defaultSessionId}）。
     *
     * @param requirementId 需求 ID
     * @param node          节点实例
     * @return DSH 会话标识；解析不到返回 null
     */
    public String resolveDefaultSessionId(Long requirementId, RequirementNode node) {
        AgentSession session = resolveDefaultSession(requirementId, node);
        return session == null ? null : session.getDshSessionId();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private SessionVO toVO(AgentSession session, boolean nodePrimary) {
        return new SessionVO(session.getId(), session.getRequirementId(), session.getDshSessionId(),
                session.getTitle(), SessionVO.toOffset(session.getLastActiveAt()),
                session.getArchived() != null && session.getArchived() == 1, nodePrimary);
    }

    /**
     * 取需求（"需求不存在"先于权限判定）。
     *
     * @param requirementId 需求 ID
     * @return 需求
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

    private AgentSession requireExistingSession(Long sessionId) {
        AgentSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private static String requireDshSessionId(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "dshSessionId 不能为空");
        }
        if (trimmed.length() > MAX_DSH_SESSION_ID_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "dshSessionId 不能超过 " + MAX_DSH_SESSION_ID_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String trimmed = title.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "会话标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
        return trimmed;
    }

    /** 当前业务时间（毫秒精度，与 DATETIME(3) 一致） */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
