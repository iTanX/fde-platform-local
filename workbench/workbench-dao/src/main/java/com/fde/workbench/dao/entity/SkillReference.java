package com.fde.workbench.dao.entity;

/**
 * 技能被引用的位置（删除校验的返回项，docs/api.md §5 DELETE 的引用清单）。
 * <p>
 * 每条记录对应一个流程模板节点或需求节点快照，只读投影。
 */
public class SkillReference {

    /** 引用来源：workflow（流程模板）或 requirement（需求快照） */
    private String kind;

    private Long workflowId;
    private String workflowName;

    /** 引用该技能的需求 ID */
    private Long requirementId;

    /** 需求标题（供前端拼出"被『客户工单分诊需求』的『需求拆解』节点引用"） */
    private String requirementTitle;

    /** 流程节点名称或需求节点快照名称 */
    private String nodeName;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

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
