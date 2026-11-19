package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.ProjectMember;
import com.fde.workbench.dao.entity.ProjectMemberDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 项目成员 Mapper，对应表 {@code wb_project_member}；SQL 见 {@code mapper/ProjectMemberMapper.xml}。
 * <p>
 * 成员判定与 owner 双源校验读取本表；成员增删、角色变更由 {@code ProjectMemberService}（T1-2）在事务内调用。
 * 所有写操作都不带乐观锁，项目的 {@code revision} 由 {@link ProjectMapper} 在同事务内推进。
 */
@Mapper
public interface ProjectMemberMapper {

    /**
     * 查询某用户在某项目中的成员记录（用于成员判定与 owner 判定）。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @return 成员记录；不是成员返回 null
     */
    ProjectMember selectByProjectAndUser(@Param("projectId") Long projectId,
                                         @Param("userId") Long userId);

    /**
     * 查询项目成员列表（关联 {@code wb_user} 取用户名与展示名）。
     * <p>
     * 排序：owner 在前，其余按加入时间、用户 ID 升序，保证同一次请求结果稳定。
     *
     * @param projectId 项目 ID
     * @return 成员列表，无成员时为空列表
     */
    List<ProjectMemberDetail> selectByProject(@Param("projectId") Long projectId);

    /**
     * 插入成员记录，时间列由应用写入。
     *
     * @param member 成员实体
     * @return 影响行数
     */
    int insert(ProjectMember member);

    /**
     * 更新成员角色。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @param role      新角色（owner / member）
     * @return 影响行数（1 成功，0 表示该用户已不在项目中）
     */
    int updateRole(@Param("projectId") Long projectId,
                   @Param("userId") Long userId,
                   @Param("role") String role);

    /**
     * 删除成员记录。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @return 影响行数（1 成功，0 表示该用户已不是成员）
     */
    int deleteByProjectAndUser(@Param("projectId") Long projectId,
                               @Param("userId") Long userId);
}
