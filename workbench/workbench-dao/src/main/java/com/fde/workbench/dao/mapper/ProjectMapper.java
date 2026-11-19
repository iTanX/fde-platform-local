package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.Project;
import com.fde.workbench.dao.entity.ProjectWithStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目 Mapper，对应表 {@code wb_project}；SQL 见 {@code mapper/ProjectMapper.xml}。
 * <p>
 * 所有查询默认排除 {@code deleted=1}（逻辑删除）的项目。
 */
@Mapper
public interface ProjectMapper {

    /**
     * 分页统计"我是成员且未删除"的项目总数。
     *
     * @param userId  当前登录用户
     * @param keyword 名称模糊匹配词，可空
     * @return 总数
     */
    long countMine(@Param("userId") Long userId, @Param("keyword") String keyword);

    /**
     * 分页查询"我是成员且未删除"的项目，附带成员数与需求数。
     *
     * @param userId  当前登录用户
     * @param keyword 名称模糊匹配词，可空
     * @param offset  偏移量
     * @param size    每页条数
     * @return 项目列表（created_at 倒序）
     */
    List<ProjectWithStats> selectMine(@Param("userId") Long userId,
                                      @Param("keyword") String keyword,
                                      @Param("offset") int offset,
                                      @Param("size") int size);

    /**
     * 按主键查询未删除的项目（项目详情/权限判定的统一入口）。
     *
     * @param id 项目 ID
     * @return 项目（含成员数/需求数统计列）；不存在或已删除返回 null
     */
    ProjectWithStats selectById(@Param("id") Long id);

    /**
     * 插入项目，自增主键回填到 {@code project.id}；时间列由应用写入。
     *
     * @param project 项目实体
     * @return 影响行数
     */
    int insert(Project project);

    /**
     * 按 revision 乐观锁更新名称/简介。
     * <p>
     * WHERE 同时带 {@code revision} 与 {@code deleted=0}；返回 0 表示被并发修改或已删除，
     * 由 Service 再查一次区分 10402 / 10301。
     *
     * @param id          项目 ID
     * @param name        新名称
     * @param description 新简介
     * @param revision    提交时携带的版本号
     * @param updatedAt   更新时间（应用写入）
     * @return 影响行数（1 成功，0 冲突）
     */
    int updateWithRevision(@Param("id") Long id,
                           @Param("name") String name,
                           @Param("description") String description,
                           @Param("revision") Integer revision,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 按 revision 乐观锁变更 owner。
     * <p>
     * 仅转让（T1-2）使用：必须与"成员表 role 变更"在同一事务内调用，两处任一步失败整体回滚。
     * 带 revision 条件，避免"读到旧版本就转让"覆盖别人刚完成的转让或编辑。
     *
     * @param id         项目 ID
     * @param newOwnerId 新 owner 用户 ID
     * @param revision   提交时携带的版本号
     * @param updatedAt  更新时间（应用写入）
     * @return 影响行数（1 成功，0 冲突）
     */
    int updateOwnerWithRevision(@Param("id") Long id,
                                @Param("newOwnerId") Long newOwnerId,
                                @Param("revision") Integer revision,
                                @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 逻辑删除：{@code deleted=1}、{@code revision+1}。
     * <p>
     * 带上 {@code revision} 条件，避免"别人刚改完就被删"的覆盖；0 行同样由 Service 区分原因。
     *
     * @param id        项目 ID
     * @param revision  提交时携带的版本号
     * @param updatedAt 更新时间（应用写入）
     * @return 影响行数（1 成功，0 冲突）
     */
    int markDeleted(@Param("id") Long id,
                    @Param("revision") Integer revision,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
