package com.fde.workbench.dao.entity;

/**
 * 技能被引用的位置（删除校验的返回项，docs/api.md §5 DELETE 的引用清单）。
 * <p>
 * 一个记录代表"某需求的某个节点实例在 skills_snapshot 里仍引用着该技能"；
 * 只读投影，不落库。
 */
public class SkillReference {

    /** 引用该技能的需求 ID */
    private Long requirementId;

    /** 需求标题（供前端拼出"被『客户工单分诊需求』的『需求拆解』节点引用"） */
    private String requirementTitle;

    /** 节点实例名称（快照名称，不随流程改名变化） */
    private String nodeName;

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public String getRequirementTitle() {
        return requirementTitle;
    }

    public void setRequirementTitle(String requirementTitle) {
        this.requirementTitle = requirementTitle;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }
}
