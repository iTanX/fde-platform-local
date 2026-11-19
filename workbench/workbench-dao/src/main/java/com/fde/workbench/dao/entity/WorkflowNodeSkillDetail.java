package com.fde.workbench.dao.entity;

/**
 * 流程节点技能详情行（{@code WorkflowMapper.selectNodeSkillsDetail} 的投影）：
 * 节点与技能的关联 + 技能当前版本信息。
 * <p>
 * 为什么单独查一次而不是复用 {@code selectNodeDetails} 的展平行：
 * <ul>
 *   <li>{@code selectNodeDetails} 没有 {@code current_version_id} / {@code version_no} 两列，
 *       而需求创建时的 {@code skills_snapshot} 必须带上这两项（PRD AC-F3.2 按版本加载技能）；</li>
 *   <li>该语句若加两列，会让"技能表 LEFT JOIN"的行数在"技能多版本"时不稳；单独一条按节点排序的
 *       查询更直观，也便于 T3-1 的既有逻辑零改动。</li>
 * </ul>
 * 技能刚创建、版本尚未回填时 {@code currentVersionId} 与 {@code currentVersionNo} 同为 null，
 * 快照里对应写成 {@code "versionId": null, "versionNo": null}（如实反映"当时没有版本"）。
 */
public class WorkflowNodeSkillDetail {

    /** 流程节点 ID */
    private Long nodeId;

    /** 节点内加载顺序（wb_workflow_node_skill.seq） */
    private Integer nodeSkillSeq;

    /** 技能 ID（删除校验的匹配键） */
    private Long skillId;

    /** 技能名称（快照冗余，技能改名后历史需求仍显示创建时的名称） */
    private String skillName;

    /** 技能当前版本 ID，可空 */
    private Long currentVersionId;

    /** 技能当前版本号，可空 */
    private Integer currentVersionNo;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getNodeSkillSeq() {
        return nodeSkillSeq;
    }

    public void setNodeSkillSeq(Integer nodeSkillSeq) {
        this.nodeSkillSeq = nodeSkillSeq;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public Long getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(Long currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public Integer getCurrentVersionNo() {
        return currentVersionNo;
    }

    public void setCurrentVersionNo(Integer currentVersionNo) {
        this.currentVersionNo = currentVersionNo;
    }
}
