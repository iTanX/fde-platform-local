package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.Project;
import com.fde.workbench.dao.entity.ProjectMember;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.mapper.ProjectMapper;
import com.fde.workbench.dao.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Service;

/**
 * 项目权限判定的唯一入口（docs/api.md §1.6 权限记号、PRD REQ-H2/H3/H4）。
 * <p>
 * 所有带 {@code projectId} 的 service 方法都应通过本类做三件事，禁止各自再写一套：
 * <ol>
 *   <li>{@link #requireExistingProject(Long)}：项目不存在或已逻辑删除 → 404/10301；</li>
 *   <li>{@link #requireMember(Long, Long)}：非成员 → 403/10201；</li>
 *   <li>{@link #requireOwner(Long, Long)}：成员但不是 owner → 403/10202。</li>
 * </ol>
 * 调用顺序固定为"存在 → 成员 → owner"：非成员一律先被 10201 拦下，避免通过 10202 / 10301 的差异
 * 探测"该项目里谁是 owner"；已存在但非成员的项目，也不泄露其内部资源。
 */
@Service
public class ProjectAccessService {

    /** 成员角色：owner（与 {@code wb_project_member.role} 取值一致） */
    public static final String ROLE_OWNER = "owner";

    /** 成员角色：普通成员 */
    public static final String ROLE_MEMBER = "member";

    private final ProjectMapper projectMapper;

    private final ProjectMemberMapper projectMemberMapper;

    public ProjectAccessService(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    /**
     * 取未删除的项目，不存在直接 404。
     *
     * @param projectId 项目 ID
     * @return 项目实体（含成员数 / 需求数统计列）
     * @throws BizException 404/10301
     */
    public ProjectWithStats requireExistingProject(Long projectId) {
        ProjectWithStats project = projectId == null ? null : projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    /**
     * 校验成员身份：成员表存在记录即可访问项目内资源。
     * <p>
     * 只查成员表，不查项目是否存在：调用方通常已先调用 {@link #requireExistingProject}，
     * 避免每个请求都多查一次项目行。
     *
     * @param projectId 项目 ID
     * @param userId    当前用户
     * @return 成员记录（调用方可能要用其 role）
     * @throws BizException 非成员 → 403/10201
     */
    public ProjectMember requireMember(Long projectId, Long userId) {
        ProjectMember member = projectMemberMapper.selectByProjectAndUser(projectId, userId);
        if (member == null) {
            throw BizException.of(ErrorCode.NOT_PROJECT_MEMBER, "你不是该项目成员");
        }
        return member;
    }

    /**
     * 校验 owner 身份（双源校验），非 owner 一律 403/10202。
     *
     * @param projectId 项目 ID
     * @param userId    当前用户
     * @return 项目实体（校验过程中已查出，供调用方复用，避免重复查询）
     * @throws BizException 项目不存在 → 404/10301；非成员 → 403/10201；成员但非 owner → 403/10202
     */
    public ProjectWithStats requireOwner(Long projectId, Long userId) {
        ProjectWithStats project = requireExistingProject(projectId);
        // 先成员后 owner：非成员必须是 10201，而不是 10202（PRD AC-H2.2、AC-C2.3）
        requireMember(projectId, userId);
        if (!isOwner(project, userId)) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有项目 owner 可以执行该操作");
        }
        return project;
    }

    /**
     * owner 判定：<b>双源校验</b>——{@code wb_project.owner_id == userId} <b>且</b> 成员表存在
     * 该用户 {@code role=owner} 的记录，两者必须同时成立。
     * <p>
     * 为什么要两处都看：
     * <ul>
     *   <li>{@code owner_id} 是项目表上的冗余字段，项目列表/详情读它最快；</li>
     *   <li>成员表的 {@code role} 才是成员管理的权威数据（转让、移除都以它为准）；</li>
     *   <li>二者本应由"转让"事务同时更新。若只改了一处（如人工改库、事务被拆开），
     *       单源判定会立刻出现"两个 owner"或"owner 无法管理自己的项目"；
     *       双源校验把这种半更新状态一律按非 owner 处理（10202），从而快速暴露问题。</li>
     * </ul>
     * 注意本方法是纯判定、不抛异常；需要 10202 时用 {@link #requireOwner}。
     *
     * @param project 项目实体
     * @param userId  当前用户
     * @return true 表示双源都为 owner
     */
    public boolean isOwner(Project project, Long userId) {
        if (project == null || userId == null || !userId.equals(project.getOwnerId())) {
            return false;
        }
        ProjectMember member = projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        return member != null && ROLE_OWNER.equals(member.getRole());
    }
}
