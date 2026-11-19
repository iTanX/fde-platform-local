package com.fde.workbench.dao.entity;

/**
 * 需求列表查询投影：需求行 + 流程名称 + 节点实例数 + 节点实例状态统计
 * （docs/api.md §1.4 RequirementVO 的 {@code workflowName} / {@code nodeCount} 数据来源）。
 * <p>
 * 这三列都是跨表统计，只读、不落库，因此单独建投影类，保持"实体 = 表结构映射"的边界
 * （与 {@link WorkflowWithStats} / {@link SkillWithStats} 同样的处理方式）。
 * <p>
 * 节点数为什么取<b>实例快照</b>（{@code wb_requirement_node}）而不是流程当前节点数：
 * 流程模板在需求创建后可能被改（增删节点），而需求读的是自己的快照（PRD AC-F2.2），
 * 进度必须按实例算，否则"3/3 已完成"的需求会显示成"3/5"。
 */
public class RequirementWithStats extends Requirement {

    /** 来源流程名称（流程已被逻辑删除时仍返回，便于历史需求展示来源） */
    private String workflowName;

    /** 节点实例总数（快照） */
    private Integer nodeCount;

    /** 已完成（{@code status='done'}）的节点实例数 */
    private Integer doneNodeCount;

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public Integer getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(Integer nodeCount) {
        this.nodeCount = nodeCount;
    }

    public Integer getDoneNodeCount() {
        return doneNodeCount;
    }

    public void setDoneNodeCount(Integer doneNodeCount) {
        this.doneNodeCount = doneNodeCount;
    }
}
