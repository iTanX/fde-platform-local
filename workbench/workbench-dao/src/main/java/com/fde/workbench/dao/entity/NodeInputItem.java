package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 节点固定输入项实体，对应表 {@code wb_node_input_item}（docs/database/001_init.sql §5）。
 * <p>
 * 只在节点 {@code input_type=fixed} 时存在（PRD AC-E3.2）；{@code required=1} 的输入项未上传时节点不能推进。
 * 输入项没有稳定 key：需求节点实例以 {@code input_item_id} 关联（见 {@code wb_requirement_node_input}）。
 */
public class NodeInputItem {

    /** 输入项主键 */
    private Long id;

    /** 所属流程节点 ID */
    private Long nodeId;

    /** 同一节点内顺序，从 1 开始 */
    private Integer seq;

    /** 输入项名称（1–128 字符） */
    private String name;

    /** 输入项说明，可空（≤500 字符） */
    private String description;

    /** 文件类型：text=md/txt，document=docx/pdf/xlsx/pptx，any=不限 */
    private String fileKind;

    /** 是否必填：1 必填，0 可跳过 */
    private Integer required;

    /** 创建时间（应用写入） */
    private LocalDateTime createdAt;

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

    public String getFileKind() {
        return fileKind;
    }

    public void setFileKind(String fileKind) {
        this.fileKind = fileKind;
    }

    public Integer getRequired() {
        return required;
    }

    public void setRequired(Integer required) {
        this.required = required;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
