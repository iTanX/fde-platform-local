package com.fde.workbench.dao.entity;

/**
 * 项目统计行（非表实体）：项目列表/详情联表统计出的成员数与需求数。
 * <p>
 * 与 {@link Project} 字段一一对应，额外带 {@code memberCount} / {@code requirementCount} 两列，
 * 避免列表接口逐行再查两次统计（N+1）。
 */
public class ProjectWithStats extends Project {

    /** 项目成员数（含 owner） */
    private Integer memberCount;

    /** 项目需求数 */
    private Integer requirementCount;

    public Integer getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(Integer memberCount) {
        this.memberCount = memberCount;
    }

    public Integer getRequirementCount() {
        return requirementCount;
    }

    public void setRequirementCount(Integer requirementCount) {
        this.requirementCount = requirementCount;
    }
}
