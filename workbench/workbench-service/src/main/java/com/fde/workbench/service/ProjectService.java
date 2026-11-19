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
 *   <li>详情要求成员（否则 403/10201，不泄露资源是否存在）；编辑/删除要求 owner（否则 403/10202），
 *       判定统一委托 {@link ProjectAccessService}（双源校验），本类不再自建权限逻辑；</li>
 *   <li>编辑/删除带 revision 乐观锁，冲突 409/10402。</li>
 * </ul>
 */
@Service
public class ProjectService {

    /** 成员角色：owner（值来源统一在 {@link ProjectAccessService#ROLE_OWNER}） */
    public static final String ROLE_OWNER = ProjectAccessService.ROLE_OWNER;

    /** 成员角色：普通成员（值来源统一在 {@link ProjectAccessService#ROLE_MEMBER}） */
    public static final String ROLE_MEMBER = ProjectAccessService.ROLE_MEMBER;

    /** 项目简介最大长度（与 wb_project.description VARCHAR(1000) 一致） */
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    private final ProjectMapper projectMapper;

    private final ProjectMemberMapper projectMemberMapper;

    private final CurrentUserContext currentUserContext;

    /** 权限判定统一走 {@link ProjectAccessService}，不在本类重复实现成员/owner 规则 */
    private final ProjectAccessService projectAccessService;

    public ProjectService(ProjectMapper projectMapper,
                          ProjectMemberMapper projectMemberMapper,
                          CurrentUserContext currentUserContext,
                          ProjectAccessService projectAccessService) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.currentUserContext = currentUserContext;
        this.projectAccessService = projectAccessService;
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
        ProjectWithStats project = projectAccessService.requireExistingProject(projectId);
        projectAccessService.requireMember(projectId, userId);
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
        // 先判成员再判 owner：非成员一律 10201，符合 api.md §1.6 权限记号与 PRD AC-H2.2。
        // 双源校验与"非 owner → 10202"统一由 ProjectAccessService 负责。
        projectAccessService.requireOwner(projectId, login.userId());
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "项目名称不能为空");
        }

        int affected = projectMapper.updateWithRevision(projectId, trimmedName,
                normalizeDescription(description), revision, LocalDateTime.now());
        if (affected == 0) {
            // 更新 0 行只有两种原因：版本号不是最新（乐观锁冲突）或期间被删除
            projectAccessService.requireExistingProject(projectId);
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
        Long userId = currentUserContext.require().userId();
        projectAccessService.requireOwner(projectId, userId);
        int affected = projectMapper.markDeleted(projectId, revision, LocalDateTime.now());
        if (affected == 0) {
            projectAccessService.requireExistingProject(projectId);
            throw BizException.of(ErrorCode.REVISION_CONFLICT, "项目已被他人修改，请刷新后重试");
        }
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
