package com.fde.workbench.dao.entity;

/**
 * 流程列表查询投影：流程行 + 节点数（docs/api.md §1.4 WorkflowVO.nodeCount 的数据来源）。
 * <p>
 * 节点数是跨表统计列（{@code wb_workflow_node}），只读、不落库；单独建投影类，
 * 保持"实体 = 表结构映射"的边界（与 {@link SkillWithStats} 同样的处理方式）。
 */
public class WorkflowWithStats extends Workflow {

    /** 节点数 */
    private Integer nodeCount;

    public Integer getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(Integer nodeCount) {
        this.nodeCount = nodeCount;
    }
}
