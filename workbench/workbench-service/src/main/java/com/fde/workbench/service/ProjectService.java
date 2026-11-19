package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.result.PageResult;
import com.fde.workbench.dao.entity.Project;
import com.fde.workbench.dao.entity.ProjectMember;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.mapper.ProjectMapper;
import com.fde.workbench.dao.mapper.ProjectMemberMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.bo.PageQuery;
import com.fde.workbench.service.security.CurrentUserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目服务：列表、创建、详情、编辑、逻辑删除（docs/api.md §3，PRD REQ-B1~B3）。
 * <p>
 * 权限与事务规则都在这里：
 * <ul>
 *   <li>列表只返回"我是成员"的项目；</li>
 *   <li>创建项目与写入 owner 成员记录在同一事务，保证不会出现"有项目无 owner"；</li>
 *   <li>详情要求成员（否则 403/10201，不泄露资源是否存在）；编辑/删除要求 owner（否则 403/10202）；</li>
 *   <li>编辑/删除带 revision 乐观锁，冲突 409/10402。</li>
 * </ul>
 */
@Service
public class ProjectService {

    /** 成员角色：owner */
    public static final String ROLE_OWNER = "owner";

    /** 成员角色：普通成员 */
    public static final String ROLE_MEMBER = "member";

    /** 项目简介最大长度（与 wb_project.description VARCHAR(1000) 一致） */
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    private final ProjectMapper projectMapper;

    private final ProjectMemberMapper projectMemberMapper;

    private final CurrentUserContext currentUserContext;

    public ProjectService(ProjectMapper projectMapper,
                          ProjectMemberMapper projectMemberMapper,
                          CurrentUserContext currentUserContext) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.currentUserContext = currentUserContext;
    }

    /**
     * 分页查询我所属的项目。
     *
     * @param keyword 名称模糊匹配词，可空
     * @param page    分页参数
     * @return 分页结果
     */
    public PageResult<ProjectWithStats> listMine(String keyword, PageQuery page) {
        Long userId = currentUserContext.require().userId();
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        long total = projectMapper.countMine(userId, kw);
        if (total == 0) {
            return PageResult.of(List.of(), 0L, page.page(), page.size());
        }
        List<ProjectWithStats> items = projectMapper.selectMine(userId, kw, page.offset(), page.size());
        return PageResult.of(items, total, page.page(), page.size());
    }

    /**
     * 创建项目：创建人即 owner，并同事务写入 {@code wb_project_member(role=owner)}。
     * <p>
     * 顺序：先插项目拿到自增 ID，再插成员记录；任一步失败整体回滚（不允许出现无成员的项目）。
     *
     * @param name        项目名称（1–128 字符，Controller 已做基础校验）
     * @param description 项目简介，可空
     * @return 新项目（含统计列）
     */
    @Transactional
    public ProjectWithStats create(String name, String description) {
        CurrentUser login = currentUserContext.require();
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "项目名称不能为空");
        }
        String desc = normalizeDescription(description);

        LocalDateTime now = LocalDateTime.now();
        Project project = new Project();
        project.setName(trimmedName);
        project.setDescription(desc);
        project.setOwnerId(login.userId());
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);

        ProjectMember owner = new ProjectMember();
        owner.setProjectId(project.getId());
        owner.setUserId(login.userId());
        owner.setRole(ROLE_OWNER);
        owner.setCreatedAt(now);
        projectMemberMapper.insert(owner);

        return projectMapper.selectById(project.getId());
    }

    /**
     * 项目详情：仅成员可见。
     *
     * @param projectId 项目 ID
     * @return 项目（含统计列）
     * @throws BizException 项目不存在 → 404/10301；非成员 → 403/10201
     */
    public ProjectWithStats detail(Long projectId) {
        Long userId = currentUserContext.require().userId();
        ProjectWithStats project = requireExistingProject(projectId);
        requireMember(projectId, userId);
        return project;
    }

    /**
     * 编辑项目名称/简介：仅 owner，带 revision 乐观锁。
     *
     * @param projectId   项目 ID
     * @param name        新名称
     * @param description 新简介，可空
     * @param revision    前端表单版本号
     * @return 更新后的项目
     * @throws BizException 404/10301、403/10201、403/10202、409/10402
     */
    @Transactional
    public ProjectWithStats update(Long projectId, String name, String description, Integer revision) {
        CurrentUser login = currentUserContext.require();
        ProjectWithStats project = requireExistingProject(projectId);
        // 先判成员再判 owner：非成员一律 10201，符合 api.md §1.5 权限记号与 PRD AC-H2.2
        requireMember(projectId, login.userId());        if (!isOwner(project, login.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有项目 owner 可以编辑项目");
        }
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "项目名称不能为空");
        }

        int affected = projectMapper.updateWithRevision(projectId, trimmedName,
                normalizeDescription(description), revision, LocalDateTime.now());
        if (affected == 0) {
            // 更新 0 行只有两种原因：版本号不是最新（乐观锁冲突）或期间被删除
            requireExistingProject(projectId);
            throw BizException.of(ErrorCode.REVISION_CONFLICT, "项目已被他人修改，请刷新后重试");
        }
        return projectMapper.selectById(projectId);
    }

    /**
     * 逻辑删除项目：仅 owner，带 revision 乐观锁。
     * <p>
     * 使用 {@code deleted=1} 而非物理删除：项目下面挂着成员、技能、流程、需求等多层级外键，
     * 物理删除会触发大量级联清理，且需求/会话的审计价值会丢失。删除后所有成员的列表都不再出现该项目。
     *
     * @param projectId 项目 ID
     * @param revision  前端表单版本号
     * @throws BizException 404/10301、403/10201、403/10202、409/10402
     */
    @Transactional
    public void delete(Long projectId, Integer revision) {
        CurrentUser login = currentUserContext.require();
        ProjectWithStats project = requireExistingProject(projectId);
        requireMember(projectId, login.userId());
        if (!isOwner(project, login.userId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有项目 owner 可以删除项目");
        }
        int affected = projectMapper.markDeleted(projectId, revision, LocalDateTime.now());
        if (affected == 0) {
            requireExistingProject(projectId);
            throw BizException.of(ErrorCode.REVISION_CONFLICT, "项目已被他人修改，请刷新后重试");
        }
    }

    /**
     * owner 判定：以 {@code wb_project.owner_id} 为准，并额外确认成员表里确有 owner 记录。
     * <p>
     * 之所以两者都要：owner_id 是项目表上的冗余字段，成员表 role 才是成员管理的权威数据；
     * 二者是应用保证的强一致性，校验双保险可避免"数据被人工改动"后越权。
     *
     * @param project 项目实体
     * @param userId  当前用户
     * @return true 表示 owner
     */
    private boolean isOwner(Project project, Long userId) {
        if (!userId.equals(project.getOwnerId())) {
            return false;
        }
        ProjectMember member = projectMemberMapper.selectByProjectAndUser(project.getId(), userId);
        return member != null && ROLE_OWNER.equals(member.getRole());
    }

    /**
     * 成员判定：成员表存在记录即可访问。
     *
     * @param projectId 项目 ID
     * @param userId    当前用户
     * @throws BizException 非成员 → 403/10201
     */
    private void requireMember(Long projectId, Long userId) {
        if (projectMemberMapper.selectByProjectAndUser(projectId, userId) == null) {
            throw BizException.of(ErrorCode.NOT_PROJECT_MEMBER, "你不是该项目成员");
        }
    }

    /**
     * 取未删除的项目（含统计列），不存在直接 404。
     *
     * @param projectId 项目 ID
     * @return 项目实体
     */
    private ProjectWithStats requireExistingProject(Long projectId) {
        ProjectWithStats project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    /**
     * 简介规整：空白转 null，超长直接拒绝（避免 MySQL 非严格模式下被静默截断）。
     */
    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "项目简介不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        return trimmed;
    }
}
