package com.fde.workbench.dao.entity;

/**
 * 技能列表查询投影：技能行 + 当前版本号 + 引用数（docs/api.md §1.4 SkillVO 的数据来源）。
 * <p>
 * 当前版本号与引用数都是跨表统计列，只读、不落库；单独建投影类而不是塞进 {@link Skill}，
 * 是为了让"实体 = 表结构的映射"这条边界保持清晰。
 */
public class SkillWithStats extends Skill {

    /** 当前生效版本号；技能没有任何版本时为 null */
    private Integer currentVersionNo;

    /** 引用数：引用该技能的流程节点数（{@code wb_workflow_node_skill}） */
    private Integer usageCount;

    public Integer getCurrentVersionNo() {
        return currentVersionNo;
    }

    public void setCurrentVersionNo(Integer currentVersionNo) {
        this.currentVersionNo = currentVersionNo;
    }

    public Integer getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(Integer usageCount) {
        this.usageCount = usageCount;
    }
}
