package com.fde.workbench.dao.entity;

/**
 * 节点可用技能关联实体，对应表 {@code wb_workflow_node_skill}（docs/database.md §5.1 多技能，修正 M3）。
 * <p>
 * 一个流程节点可关联多个技能，{@code seq} 决定提示与加载顺序（PRD AC-E5.1/E5.2）。
 * 该表只存关联，查询时再 JOIN {@code wb_skill} 取名称（技能被逻辑删除后名称仍可展示）。
 */
public class WorkflowNodeSkill {

    /** 关联主键 */
    private Long id;

    /** 流程节点 ID */
    private Long nodeId;

    /** 技能 ID */
    private Long skillId;

    /** 提示与加载顺序，从 1 开始 */
    private Integer seq;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }
}
