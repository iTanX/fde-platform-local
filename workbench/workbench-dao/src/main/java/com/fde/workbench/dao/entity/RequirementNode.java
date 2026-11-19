package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 需求节点实例实体，对应表 {@code wb_requirement_node}（docs/database/001_init.sql §6）。
 * <p>
 * 创建需求时按流程节点<b>顺序</b>生成实例并把名称 / 说明 / 输入类型 / 输入项 / 输出项 / 技能
 * 整体快照到本行（PRD AC-F2.2：之后修改流程模板不影响已有需求）。三个 JSON 列在数据库里是
 * {@code JSON} 类型，MyBatis 按 {@link String} 取回，结构化解析与组装放在 Service 层
 * （JSON 的键名是接口契约的一部分，不宜让数据库驱动或类型处理器去猜）。
 * <p>
 * 状态机：{@code waiting}（未开始）→ {@code current}（当前节点，写 {@code startedAt}）
 * → {@code done}（全部必填输出提交完成，写 {@code completedAt} / {@code completedBy}）。
 * 节点推进本身属于 T5-1，本类只承载 T4-1 创建时写入的初始状态。
 */
public class RequirementNode {

    /** 节点实例状态：未开始 */
    public static final String STATUS_WAITING = "waiting";

    /** 节点实例状态：当前节点 */
    public static final String STATUS_CURRENT = "current";

    /** 节点实例状态：已完成 */
    public static final String STATUS_DONE = "done";

    /** 节点实例主键（对外即 api.md 的 nodeId / nodeInstanceId） */
    private Long id;

    /** 所属需求 ID */
    private Long requirementId;

    /** 来源流程节点 ID（仅用于追溯，模板改动后不再引用它取数） */
    private Long workflowNodeId;

    /** 执行顺序，从 1 开始；同一需求内唯一（uk_req_node_seq） */
    private Integer seq;

    /** 快照：节点名称 */
    private String name;

    /** 快照：节点说明，可空 */
    private String description;

    /** 快照：输入类型 free / fixed */
    private String inputType;

    /** 快照：输入项 JSON 数组字符串 */
    private String inputsSnapshot;

    /** 快照：输出项 JSON 数组字符串 */
    private String outputsSnapshot;

    /** 快照：技能 JSON 数组字符串 */
    private String skillsSnapshot;

    /** 本节点默认打开的会话（修正 M4，由 T5-1 写入） */
    private Long primarySessionId;

    /** 状态：waiting / current / done */
    private String status;

    /** 成为当前节点的时间 */
    private LocalDateTime startedAt;

    /** 全部必填输出项提交完成的时间 */
    private LocalDateTime completedAt;

    /** 完成人（T5-1 推进时写入） */
    private Long completedBy;

    /** 创建时间（应用写入） */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public Long getWorkflowNodeId() {
        return workflowNodeId;
    }

    public void setWorkflowNodeId(Long workflowNodeId) {
        this.workflowNodeId = workflowNodeId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getInputsSnapshot() {
        return inputsSnapshot;
    }

    public void setInputsSnapshot(String inputsSnapshot) {
        this.inputsSnapshot = inputsSnapshot;
    }

    public String getOutputsSnapshot() {
        return outputsSnapshot;
    }

    public void setOutputsSnapshot(String outputsSnapshot) {
        this.outputsSnapshot = outputsSnapshot;
    }

    public String getSkillsSnapshot() {
        return skillsSnapshot;
    }

    public void setSkillsSnapshot(String skillsSnapshot) {
        this.skillsSnapshot = skillsSnapshot;
    }

    public Long getPrimarySessionId() {
        return primarySessionId;
    }

    public void setPrimarySessionId(Long primarySessionId) {
        this.primarySessionId = primarySessionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Long getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(Long completedBy) {
        this.completedBy = completedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
