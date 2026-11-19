package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 流程节点实体，对应表 {@code wb_workflow_node}（docs/database/001_init.sql §5）。
 * <p>
 * {@code seq} 即执行顺序，从 1 开始、同一流程内唯一（{@code uk_node_workflow_seq}）；
 * 整体保存节点时按请求数组下标重排，因此"数组顺序 = 执行顺序"（PRD AC-E2.1）。
 * <p>
 * 节点是<b>物理删除</b>：删除前必须先删掉它的输出项、输入项、技能关联（外键为 RESTRICT）。
 */
public class WorkflowNode {

    /** 输入类型：自由输入（整个节点一个上传入口，忽略固定输入项） */
    public static final String INPUT_TYPE_FREE = "free";

    /** 输入类型：固定输入项（逐项上传） */
    public static final String INPUT_TYPE_FIXED = "fixed";

    /** 节点主键 */
    private Long id;

    /** 所属流程 ID */
    private Long workflowId;

    /** 执行顺序，从 1 开始 */
    private Integer seq;

    /** 节点名称（1–128 字符） */
    private String name;

    /** 节点说明，可空（≤1000 字符） */
    private String description;

    /** 输入类型：free / fixed */
    private String inputType;

    /** 创建时间（应用写入） */
    private LocalDateTime createdAt;

    /** 更新时间（应用写入） */
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
