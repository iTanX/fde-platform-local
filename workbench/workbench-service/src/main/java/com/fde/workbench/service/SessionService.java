package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.AgentSession;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.Requirement;
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
 * DSH 会话由插件创建。登记或显式切换会话时，在同一个事务中更新当前节点默认会话、
 * 会话运行位置及节点与会话的历史关联。
 * <p>
 * 默认会话优先读取节点的 {@code primary_session_id}，其次读取需求下最近活跃的未归档会话。
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
        return doRegister(requirementId, command, user.userId());
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
     * @param command       登记命令（{@code nodeId} 为空时只登记到需求）
     * @return 新会话
     * @throws BizException 需求不存在 404/10301；参数非法 400/10001；重复 409/10401
     */
    public SessionVO registerInternal(Long requirementId, SessionRegisterCommand command) {
        return doRegister(requirementId, command, null);
    }

    private SessionVO doRegister(Long requirementId, SessionRegisterCommand command, Long createdBy) {
        String dshSessionId = requireDshSessionId(command == null ? null : command.dshSessionId());
        String title = normalizeTitle(command.title());
        Long nodeId = command.nodeId();
        AgentSession saved;
        try {
            saved = transactionTemplate.execute(status -> {
                Requirement requirement = lockRequirement(requirementId);
                if (sessionMapper.selectByDshSessionId(dshSessionId) != null) {
                    throw BizException.of(ErrorCode.DUPLICATE_NAME, "该 DSH 会话已登记过：" + dshSessionId);
                }
                if (nodeId != null) {
                    requireCurrentNode(requirement, nodeId);
                }
                LocalDateTime now = now();
                AgentSession session = new AgentSession();
                session.setRequirementId(requirementId);
                session.setDshSessionId(dshSessionId);
                session.setDshWorkspacePath(requirement.getDshWorkspacePath());
                session.setTitle(title);
                session.setCreatedBy(createdBy == null ? requirement.getCreatorId() : createdBy);
                session.setLastActiveAt(now);
                session.setArchived(0);
                session.setCreatedAt(now);
                session.setUpdatedAt(now);
                sessionMapper.insertSession(session);
                saveNodeSelection(session.getId(), nodeId, now);
                return session;
            });
        } catch (DuplicateKeyException ex) {
            // 不同需求的并发登记也受 DSH 会话标识的全局唯一键约束。
            throw BizException.of(ErrorCode.DUPLICATE_NAME, "该 DSH 会话已登记过：" + dshSessionId);
        }
        log.info("登记会话：requirementId={}, sessionId={}, dshSessionId={}, nodeId={}, createdBy={}",
                requirementId, saved.getId(), dshSessionId, nodeId, saved.getCreatedBy());
        return toVO(saved, nodeId != null);
    }

    private void requireCurrentNode(Requirement requirement, Long nodeId) {
        RequirementNode node = requirementMapper.selectNodeById(nodeId);
        if (node == null || !Objects.equals(node.getRequirementId(), requirement.getId())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "节点不属于该需求：" + nodeId);
        }
        if (!Requirement.STATUS_IN_PROGRESS.equals(requirement.getStatus())
                || !RequirementNode.STATUS_CURRENT.equals(node.getStatus())
                || !Objects.equals(node.getSeq(), requirement.getCurrentSeq())) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "只有进行中需求的当前节点可以选择会话");
        }
    }

    private void saveNodeSelection(Long sessionId, Long nodeId, LocalDateTime now) {
        sessionMapper.saveRuntimeNode(sessionId, nodeId, now);
        if (nodeId != null) {
            requirementMapper.setPrimarySession(nodeId, sessionId);
            sessionMapper.insertNodeSessionLink(nodeId, sessionId, now);
        }
    }

    // ------------------------------------------------------------------
    // touch
    // ------------------------------------------------------------------

    /**
     * 用服务端时间更新最近活跃时间（权限 C）。传入节点 ID 时同时保存显式选择。
     *
     * @param sessionId 会话 ID
     * @param nodeId    当前节点实例 ID，可空
     * @return 更新后的会话
     */
    public SessionVO touch(Long sessionId, Long nodeId) {
        CurrentUser user = currentUserContext.require();
        AgentSession session = requireExistingSession(sessionId);
        RequirementWithStats requirement = requireExistingRequirement(session.getRequirementId());
        ProjectWithStats project = projectAccessService.requireExistingProject(requirement.getProjectId());
        projectAccessService.requireMember(requirement.getProjectId(), user.userId());
        boolean owner = projectAccessService.isOwner(project, user.userId());
        if (!owner && !Objects.equals(requirement.getCreatorId(), user.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有需求创建者或项目 owner 可以更新会话活跃时间");
        }
        return transactionTemplate.execute(status -> {
            Requirement locked = lockRequirement(requirement.getId());
            AgentSession latest = requireExistingSession(sessionId);
            LocalDateTime now = now();
            if (nodeId != null) {
                requireCurrentNode(locked, nodeId);
                if (isArchived(latest)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "已归档会话不能设为默认会话");
                }
                saveNodeSelection(sessionId, nodeId, now);
            }
            sessionMapper.updateLastActive(sessionId, now);
            latest.setLastActiveAt(now);
            boolean primary = nodeId != null || requirementMapper.selectNodesByRequirement(requirement.getId()).stream()
                    .anyMatch(node -> Objects.equals(node.getPrimarySessionId(), sessionId));
            return toVO(latest, primary);
        });
    }

    // ------------------------------------------------------------------
    // 默认会话解析
    // ------------------------------------------------------------------

    /**
     * 解析某节点的默认会话（api.md §9 的三步顺序），优先使用节点上仍有效的选择。
     *
     * @param requirementId 需求 ID
     * @param node          节点实例（可空：为空时只看需求级最近活跃）
     * @return 会话；解析不到返回 null
     */
    public AgentSession resolveDefaultSession(Long requirementId, RequirementNode node) {
        if (node != null && node.getPrimarySessionId() != null) {
            AgentSession primary = sessionMapper.selectById(node.getPrimarySessionId());
            if (primary != null && Objects.equals(primary.getRequirementId(), requirementId) && !isArchived(primary)) {
                return primary;
            }
        }
        return sessionMapper.selectMostActiveByRequirement(requirementId);
    }

    /**
     * 推进时把前一节点的默认会话保存到下一节点。调用方须处在持有需求锁的推进事务中。
     */
    void inheritDefaultSession(Long requirementId, Long previousNodeId, Long nextNodeId) {
        RequirementNode previousNode = requirementMapper.selectNodeById(previousNodeId);
        AgentSession session = resolveDefaultSession(requirementId, previousNode);
        if (session != null) {
            requirementMapper.setPrimarySession(nextNodeId, session.getId());
        }
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
                session.getTitle(), SessionVO.toOffset(session.getLastActiveAt()), isArchived(session), nodePrimary);
    }

    private static boolean isArchived(AgentSession session) {
        return Objects.equals(session.getArchived(), 1);
    }

    private Requirement lockRequirement(Long requirementId) {
        // 与节点推进共用同一需求锁，确保切换和推进按事务顺序生效。
        Requirement requirement = requirementMapper.selectByIdForUpdate(requirementId);
        if (requirement == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "需求不存在");
        }
        return requirement;
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
