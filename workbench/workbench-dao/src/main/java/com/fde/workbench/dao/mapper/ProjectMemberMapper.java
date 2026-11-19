package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.ProjectMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 项目成员 Mapper，对应表 {@code wb_project_member}；SQL 见 {@code mapper/ProjectMemberMapper.xml}。
 * <p>
 * 本任务（T1-1）只用到"查单个成员角色"与"创建项目时写入 owner 记录"，成员增删由 T1-2 实现。
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
     * 插入成员记录，时间列由应用写入。
     *
     * @param member 成员实体
     * @return 影响行数
     */
    int insert(ProjectMember member);
}
