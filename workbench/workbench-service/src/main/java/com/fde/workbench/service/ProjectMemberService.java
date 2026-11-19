package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.ProjectMember;
import com.fde.workbench.dao.entity.ProjectMemberDetail;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.User;
import com.fde.workbench.dao.mapper.ProjectMapper;
import com.fde.workbench.dao.mapper.ProjectMemberMapper;
import com.fde.workbench.dao.mapper.SkillMapper;
import com.fde.workbench.dao.mapper.UserMapper;
import com.fde.workbench.service.security.CurrentUserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 成员管理服务：成员列表、添加成员、移除成员、转让 owner（docs/api.md §4，PRD REQ-C1~C4、REQ-H2~H4）。
 * <p>
 * 权限模型（统一由 {@link ProjectAccessService} 判定，本类不另写一套）：
 * <ul>
 *   <li>列表：项目成员即可（M）；</li>
 *   <li>添加 / 移除 / 转让：仅 owner（O），判定走 <b>双源校验</b>（owner_id 且 成员表 role=owner）。</li>
 * </ul>
 * 事务规则（api.md §4 明确要求）：
 * <ul>
 *   <li>转让：同一事务内同时更新 {@code wb_project.owner_id}（带 revision 乐观锁）、
 *       目标用户 role→owner、原 owner role→member；任一步失败整体回滚；</li>
 *   <li>移除：同一事务内先删该成员在本项目创建的技能（含各版本），再删成员记录；
 *       移除前先由 {@link MemberDataChecker} 确认该成员在本项目内没有需求/流程/文件/会话，
 *       若仍有（或删除技能因外键失败），整体回滚并给出可读原因（409/10403）。</li>
 * </ul>
 * 说明：项目 {@code revision} 只在转让时推进（它代表可编辑聚合根的版本，owner 变更属于项目属性变更）；
 * 成员增删不推进 revision，因此移除成员的 {@code ?revision=} 用于"我看到的还是最新版本"的前置校验，
 * 而不是加锁条件——真正的并发安全由成员表唯一约束与 owner 双源校验保证。
 */
@Service
public class ProjectMemberService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMemberService.class);

    private final ProjectMemberMapper projectMemberMapper;

    private final ProjectMapper projectMapper;

    private final SkillMapper skillMapper;

    private final UserMapper userMapper;

    private final ProjectAccessService projectAccessService;

    private final CurrentUserContext currentUserContext;

    /** 移除前检查"该用户在本项目内是否仍有业务数据"（需求 / 流程 / 文件 / 会话） */
    private final MemberDataChecker memberDataChecker;

    public ProjectMemberService(ProjectMemberMapper projectMemberMapper,
                                ProjectMapper projectMapper,
                                SkillMapper skillMapper,
                                UserMapper userMapper,
                                ProjectAccessService projectAccessService,
                                CurrentUserContext currentUserContext,
                                MemberDataChecker memberDataChecker) {
        this.projectMemberMapper = projectMemberMapper;
        this.projectMapper = projectMapper;
        this.skillMapper = skillMapper;
        this.userMapper = userMapper;
        this.projectAccessService = projectAccessService;
        this.currentUserContext = currentUserContext;
        this.memberDataChecker = memberDataChecker;
    }

    /**
     * 成员列表：项目成员即可查看。
     *
     * @param projectId 项目 ID
     * @return 成员列表（owner 在前，其余按加入时间升序）
     * @throws BizException 项目不存在 → 404/10301；非成员 → 403/10201
     */
    public List<ProjectMemberDetail> list(Long projectId) {
        Long userId = currentUserContext.require().userId();
        projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, userId);
        return projectMemberMapper.selectByProject(projectId);
    }

    /**
     * 添加成员：仅 owner。按用户名找到用户后写入 {@code role=member} 的成员记录。
     * <p>
     * 唯一性由 {@code uk_member_project_user(project_id, user_id)} 兜底：并发下的重复添加会触发
     * {@link DuplicateKeyException}，同样转换为 409/10401，而不是 500。
     *
     * @param projectId 项目 ID
     * @param username  目标用户名（Controller 已做非空校验）
     * @return 新成员（含用户名 / 展示名）
     * @throws BizException 项目不存在 404/10301；非成员 403/10201；非 owner 403/10202；
     *                      用户不存在 404/10301；已是成员 409/10401
     */
    public ProjectMemberDetail add(Long projectId, String username) {
        Long operatorId = currentUserContext.require().userId();
        projectAccessService.requireOwner(projectId, operatorId);

        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "用户名不能为空");
        }
        User target = userMapper.selectByUsername(normalized);
        if (target == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "用户不存在：" + normalized);
        }
        if (projectMemberMapper.selectByProjectAndUser(projectId, target.getId()) != null) {
            throw BizException.of(ErrorCode.DUPLICATE_NAME, "该用户已是项目成员");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(target.getId());
        member.setRole(ProjectAccessService.ROLE_MEMBER);
        member.setCreatedAt(LocalDateTime.now());
        try {
            projectMemberMapper.insert(member);
        } catch (DuplicateKeyException ex) {
            // 并发重复添加：唯一键先触发，同样按"已是成员"处理
            throw BizException.of(ErrorCode.DUPLICATE_NAME, "该用户已是项目成员");
        }
        return toDetail(member, target);
    }

    /**
     * 移除成员：仅 owner，且不能移除 owner 自己（必须先转让）。
     * <p>
     * 事务顺序严格按 api.md §4：
     * <ol>
     *   <li>删除该成员在本项目创建的全部技能（先版本、后技能）；</li>
     *   <li>删除成员记录。</li>
     * </ol>
     * 外键全部是 RESTRICT，若该用户在本项目仍留有需求、流程等数据，第 ①/② 步会抛
     * {@link DataIntegrityViolationException}；本方法将其转换为可读的 409/10403
     * 并<b>整体回滚</b>（技能不会被删、成员记录保留），绝不留下"技能没了但人还在"的中间状态。
     *
     * @param projectId 项目 ID
     * @param userId    被移除的用户 ID
     * @param revision  调用方看到的最新项目版本号，过期 → 409/10402
     * @throws BizException 项目不存在 404/10301；非成员 403/10201；非 owner 403/10202；
     *                      目标不是成员 404/10301；移除 owner 自己 409/10404；
     *                      revision 过期 409/10402；数据被引用 409/10403
     */
    @Transactional
    public void remove(Long projectId, Long userId, Integer revision) {
        Long operatorId = currentUserContext.require().userId();
        ProjectWithStats project = projectAccessService.requireOwner(projectId, operatorId);

        if (userId == null || Objects.equals(userId, project.getOwnerId())) {
            // owner 必须先转让再谈移除；否则项目会处于无人可管的状态
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "不能移除项目 owner，请先转让 owner");
        }
        ProjectMember target = projectMemberMapper.selectByProjectAndUser(projectId, userId);
        if (target == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "该用户不是项目成员");
        }
        // 这些写操作本身不带版本条件，explicitRevision 用于在进入写操作前拦下明显过期的表单
        requireNotExpired(project, revision);

        try {
            // 【重要】外键能拦住"该用户创建的需求/流程"，但拦不住"该用户参与的产出"：
            // wb_file.uploaded_by、wb_requirement_node_*.committed_by / uploaded_by、
            // wb_agent_session.created_by 这些列指向 wb_user，与"他是不是项目成员"无关。
            // 若只依赖外键，成员记录会被删掉，而项目里还留着已离职成员的产出记录 →
            // 因此这里必须显式检查该用户在本项目内是否还有任何业务数据，有则整体回滚。
            requireNoRemainingData(projectId, userId);

            int versions = skillMapper.deleteVersionsByProjectAndCreator(projectId, userId);
            int skills = skillMapper.deleteByProjectAndCreator(projectId, userId);
            projectMemberMapper.deleteByProjectAndUser(projectId, userId);
            log.info("移除成员：projectId={}, userId={}, 清理技能 {} 个 / 版本 {} 个",
                    projectId, userId, skills, versions);
        } catch (DataIntegrityViolationException ex) {
            // 抛异常即触发回滚：上面已执行的技能删除不会落库
            long skillCount = skillMapper.countByProjectAndCreator(projectId, userId);
            log.warn("移除成员因外键失败回滚：projectId={}, userId={}, 其技能数={}, 原因={}",
                    projectId, userId, skillCount, ex.getMostSpecificCause().getMessage());
            throw removalBlocked();
        }
    }

    /**
     * 移除前检查：该用户在本项目内是否仍有业务数据（需求、流程、文件、会话）。
     * <p>
     * 与 api.md §4 的顺序不冲突：技能允许被清理，所以不算"阻碍"；其余数据一旦存在，
     * 删成员记录会让项目留下指向"非成员"的数据，必须拒绝并整体回滚。
     *
     * @param projectId 项目 ID
     * @param userId    被移除的用户 ID
     * @throws BizException 409/10403
     */
    private void requireNoRemainingData(Long projectId, Long userId) {
        Map<String, Long> blockers = memberDataChecker.check(projectId, userId);
        if (!blockers.isEmpty()) {
            log.warn("移除成员被拒绝：projectId={}, userId={}, 仍存在 {}", projectId, userId, blockers);
            throw removalBlocked();
        }
    }

    /** "移除被数据引用"的统一异常（409/10403），文案面向最终用户 */
    private static BizException removalBlocked() {
        return BizException.of(ErrorCode.REFERENCED,
                "该成员在项目中仍有数据（如需求、流程或产出文件），无法移除，操作已回滚");
    }

    /**
     * 转让 owner：仅 owner，带 revision 乐观锁。
     * <p>
     * 同一事务内完成三处变更（顺序：项目行 → 目标成员 → 原 owner 成员）：
     * <ol>
     *   <li>{@code wb_project.owner_id = 目标用户}，并 {@code revision+1}；</li>
     *   <li>目标用户成员记录 {@code role: member → owner}；</li>
     *   <li>原 owner 成员记录 {@code role: owner → member}（保留成员身份，仍可读项目）。</li>
     * </ol>
     * 任一步影响 0 行（并发修改 / 成员在此期间被移除）都会抛异常整体回滚，
     * 保证库里不会出现"两个 owner"或"owner_id 指向非 owner 成员"这类半更新状态——
     * 这正是 {@link ProjectAccessService#isOwner} 双源校验要防的情况。
     *
     * @param projectId 项目 ID
     * @param userId    新的 owner 用户 ID（必须是该项目现有成员）
     * @param revision  调用方看到的最新项目版本号，过期 → 409/10402
     * @return 转让后的项目（ownerId 已更新，revision 已 +1）
     * @throws BizException 项目不存在 404/10301；非成员 403/10201；非 owner 403/10202；
     *                      目标不是成员 404/10301；目标已是 owner 409/10404；revision 过期 409/10402
     */
    @Transactional
    public ProjectWithStats transferOwner(Long projectId, Long userId, Integer revision) {
        Long operatorId = currentUserContext.require().userId();
        ProjectWithStats project = projectAccessService.requireOwner(projectId, operatorId);

        if (userId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "目标用户不能为空");
        }
        if (Objects.equals(userId, operatorId)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "不能把 owner 转让给自己");
        }
        ProjectMember target = projectMemberMapper.selectByProjectAndUser(projectId, userId);
        if (target == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "目标用户不是项目成员，无法转让");
        }

        LocalDateTime now = LocalDateTime.now();
        // ① 项目行：带 revision 条件更新 owner_id
        int affected = projectMapper.updateOwnerWithRevision(projectId, userId, revision, now);
        if (affected == 0) {
            // 0 行只可能是版本过期（项目已存在且 operator 是 owner，说明没被删除）
            throw BizException.of(ErrorCode.REVISION_CONFLICT, "项目已被他人修改，请刷新后重试");
        }
        // ② 目标用户升为 owner
        if (projectMemberMapper.updateRole(projectId, userId, ProjectAccessService.ROLE_OWNER) != 1) {
            throw new IllegalStateException("转让 owner 失败：目标成员记录在事务中被移除");
        }
        // ③ 原 owner 降为 member（保留成员身份：api.md §4 只要求 role 与 owner_id 同时变更）
        if (projectMemberMapper.updateRole(projectId, operatorId, ProjectAccessService.ROLE_MEMBER) != 1) {
            throw new IllegalStateException("转让 owner 失败：原 owner 成员记录在事务中被移除");
        }
        log.info("转让 owner：projectId={}, from={}, to={}", projectId, operatorId, userId);
        return projectMapper.selectById(projectId);
    }

    /**
     * revision 前置校验：与最新版本不一致即 409/10402。
     * <p>
     * 缺失或过期在 api.md §1.1 中都是 10402（缺失时 Controller 绑定阶段已按 10001 拒绝）。
     *
     * @param project  当前项目
     * @param revision 调用方携带的版本号
     */
    private static void requireNotExpired(ProjectWithStats project, Integer revision) {
        if (revision == null || !revision.equals(project.getRevision())) {
            throw BizException.of(ErrorCode.REVISION_CONFLICT, "项目已被他人修改，请刷新后重试");
        }
    }

    /** 新插入的成员记录 + 用户实体 → 列表投影（与 {@code selectByProject} 返回的结构一致） */
    private static ProjectMemberDetail toDetail(ProjectMember member, User user) {
        ProjectMemberDetail detail = new ProjectMemberDetail();
        detail.setId(member.getId());
        detail.setProjectId(member.getProjectId());
        detail.setUserId(member.getUserId());
        detail.setUsername(user.getUsername());
        detail.setDisplayName(user.getDisplayName());
        detail.setRole(member.getRole());
        detail.setCreatedAt(member.getCreatedAt());
        return detail;
    }
}
