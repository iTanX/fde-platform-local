package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.Skill;
import com.fde.workbench.dao.entity.SkillReference;
import com.fde.workbench.dao.entity.SkillVersion;
import com.fde.workbench.dao.entity.SkillWithStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能 Mapper，对应表 {@code wb_skill} 与 {@code wb_skill_version}；SQL 见 {@code mapper/SkillMapper.xml}。
 * <p>
 * T1-2 建立了"移除成员时清理该成员在项目内的技能（含各版本）"三条语句；
 * T2-1 在本接口上扩展技能管理所需语句（列表 / 按名查 / 插入 / 逻辑删除 / 版本追加与查询），
 * 不另建第二套 Mapper，避免同一个表出现两组不一致的 SQL。
 */
@Mapper
public interface SkillMapper {

    // ------------------------------------------------------------------
    // T2-1：技能列表与单条查询
    // ------------------------------------------------------------------

    /**
     * 项目内未删除的技能列表，附带当前版本号与流程节点引用数。
     *
     * @param projectId 项目 ID
     * @return 技能列表（创建时间倒序）
     */
    List<SkillWithStats> selectByProject(@Param("projectId") Long projectId);

    /**
     * 按主键查询未删除的技能。
     *
     * @param id      技能 ID
     * @param deleted 要求满足的逻辑删除标记（正常查询传 0）
     * @return 技能；不存在 / 已删除返回 null
     */
    Skill selectById(@Param("id") Long id);

    /**
     * 按主键查询未删除的技能，并带出当前版本号（{@link SkillWithStats#getCurrentVersionNo()}）。
     * <p>
     * 需求创建时要把技能"当前版本"快照进 {@code skills_snapshot}（含 {@code versionId} 与
     * {@code versionNo}），而 {@link Skill} 上只有 {@code current_version_id}，因此需要这一条查询。
     * {@code usageCount} 不参与本查询（保持为 null，调用方不要读）。
     *
     * @param id 技能 ID
     * @return 技能 + 当前版本号；不存在 / 已逻辑删除返回 null
     */
    SkillWithStats selectWithCurrentVersionById(@Param("id") Long id);

    /**
     * 按"项目 + 名称"查询未删除的技能，用于判断同名上传是升级还是新建。
     *
     * @param projectId 项目 ID
     * @param name      技能名
     * @return 技能；不存在返回 null
     */
    Skill selectByProjectAndName(@Param("projectId") Long projectId, @Param("name") String name);

    /**
     * 新建技能（{@code current_version_id} 先留空，版本写入后由 {@link #updateCurrentVersion} 回填）。
     *
     * @param skill 技能实体（id 会回填）
     * @return 影响行数
     */
    int insert(Skill skill);

    /**
     * 更新当前生效版本，同时刷新 {@code updated_at}。
     *
     * @param id               技能 ID
     * @param currentVersionId 新版本 ID
     * @param updatedAt        更新时间
     * @return 影响行数
     */
    int updateCurrentVersion(@Param("id") Long id,
                             @Param("currentVersionId") Long currentVersionId,
                             @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 逻辑删除技能（{@code deleted=1}）。
     * <p>
     * 只改标记，不删版本行：已完成的需求仍以 {@code skills_snapshot} 记录版本号与名称，
     * 保留版本行才能让历史需求追溯"当时用的是哪一版"。
     *
     * @param id        技能 ID
     * @param projectId 项目 ID（防止跨项目误删）
     * @param updatedAt 更新时间
     * @return 影响行数；0 表示技能不存在 / 已删除 / 不属于该项目
     */
    int markDeleted(@Param("id") Long id,
                    @Param("projectId") Long projectId,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 统计技能被活跃流程节点引用的次数，对应 SkillVO.usageCount。
     *
     * @param skillId 技能 ID
     * @return 引用数
     */
    long countNodeReferences(@Param("skillId") Long skillId);

    // ------------------------------------------------------------------
    // T2-1：版本
    // ------------------------------------------------------------------

    /**
     * 取技能的最大版本号，用于计算下一个版本号。
     *
     * @param skillId 技能 ID
     * @return 最大版本号；没有版本返回 null
     */
    Integer selectMaxVersionNo(@Param("skillId") Long skillId);

    /**
     * 追加一个版本。
     *
     * @param version 版本实体（id 会回填）
     * @return 影响行数
     */
    int insertVersion(SkillVersion version);

    /**
     * 按技能 + 版本号查询版本。
     *
     * @param skillId   技能 ID
     * @param versionNo 版本号
     * @return 版本；不存在返回 null
     */
    SkillVersion selectVersion(@Param("skillId") Long skillId, @Param("versionNo") Integer versionNo);

    /**
     * 按主键查询版本（列表里补当前版本号时使用）。
     *
     * @param id 版本 ID
     * @return 版本；不存在返回 null
     */
    SkillVersion selectVersionById(@Param("id") Long id);

    /**
     * 版本列表，版本号升序。
     *
     * @param skillId 技能 ID
     * @return 版本列表
     */
    List<SkillVersion> selectVersions(@Param("skillId") Long skillId);

    // ------------------------------------------------------------------
    // T2-1：删除前的引用校验
    // ------------------------------------------------------------------

    /**
     * 查询活跃流程模板与未完成需求节点快照中对该技能的引用（docs/api.md §5）。
     *
     * @param skillId 技能 ID
     * @return 引用清单，包含来源和节点名称
     */
    List<SkillReference> selectBlockingReferences(@Param("skillId") Long skillId);

    /** 锁定待删除的活跃技能，必须在删除事务内执行。 */
    Long lockActiveSkillForDelete(@Param("skillId") Long skillId, @Param("projectId") Long projectId);

    /** 在引用写入事务内共享锁定并返回仍可用的技能 ID；skillIds 必须非空。 */
    List<Long> lockActiveSkillsForReference(@Param("projectId") Long projectId,
                                            @Param("skillIds") List<Long> skillIds);

    /**
     * 统计某用户在某项目内创建的技能数（含已逻辑删除的）。
     * <p>
     * 移除成员前先统计：若存在技能被流程节点或未完成需求节点引用，后续删除会因外键失败而回滚，
     * 提前统计不是为了提前拒绝，而是为了让错误信息能说清"有多少个技能被引用"。
     *
     * @param projectId 项目 ID
     * @param createdBy 技能创建人
     * @return 技能数
     */
    long countByProjectAndCreator(@Param("projectId") Long projectId,
                                 @Param("createdBy") Long createdBy);

    /**
     * 物理删除某用户在某项目内创建的全部技能的所有版本。
     * <p>
     * 必须先于 {@link #deleteByProjectAndCreator} 执行：{@code fk_skill_version_skill} 是 RESTRICT，
     * 版本行还在时删技能会被外键拒绝。
     *
     * @param projectId 项目 ID
     * @param createdBy 技能创建人
     * @return 删除的版本行数
     */
    int deleteVersionsByProjectAndCreator(@Param("projectId") Long projectId,
                                          @Param("createdBy") Long createdBy);

    /**
     * 物理删除某用户在某项目内创建的全部技能。
     * <p>
     * 只检查 {@code created_by}，不按 {@code deleted} 过滤：已逻辑删除的技能同样会阻碍成员移除
     * （{@code fk_skill_creator} 指向 wb_user），且成员离开后其技能本就不该留在项目中。
     *
     * @param projectId 项目 ID
     * @param createdBy 技能创建人
     * @return 删除的技能行数
     */
    int deleteByProjectAndCreator(@Param("projectId") Long projectId,
                                  @Param("createdBy") Long createdBy);
}
