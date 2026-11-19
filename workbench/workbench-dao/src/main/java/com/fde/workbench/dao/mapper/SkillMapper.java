package com.fde.workbench.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 技能 Mapper，对应表 {@code wb_skill} 与 {@code wb_skill_version}；SQL 见 {@code mapper/SkillMapper.xml}。
 * <p>
 * 本任务（T1-2）只用到"移除成员时清理该成员在项目内的技能（含各版本）"；
 * 技能的增删改查主流程由 T2-1 扩展本接口，不要重复建另一套 Mapper。
 */
@Mapper
public interface SkillMapper {

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
