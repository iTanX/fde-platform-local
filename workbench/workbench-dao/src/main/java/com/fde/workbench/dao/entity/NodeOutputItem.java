package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 节点输出项实体，对应表 {@code wb_node_output_item}（docs/database/001_init.sql §5）。
 * <p>
 * 输出项只有"固定"一种模式（PRD AC-E4.1），每个节点至少一项；{@code item_key} 是<b>节点内唯一</b>的稳定键
 * （{@code uk_output_item_key}），模型工具按它提交产出（{@code /internal/nodes/{nid}/outputs/{itemKey}/commit}）。
 * 同一节点内 {@code (node_id, item_key)} 唯一，因此整体保存时不能把两个输出项写成同一个 key。
 */
public class NodeOutputItem {

    /** 输出项主键 */
    private Long id;

    /** 所属流程节点 ID */
    private Long nodeId;

    /** 同一节点内顺序，从 1 开始 */
    private Integer seq;

    /** 输出项稳定键：匹配 {@code ^[a-z0-9][a-z0-9-]{0,63}$}，节点内唯一 */
    private String itemKey;

    /** 输出项名称（1–128 字符） */
    private String name;

    /** 输出项要求（进入模型上下文），可空（≤500 字符） */
    private String description;

    /** 文件类型：text / document / any */
    private String fileKind;

    /** 是否必填：1 必填（未提交则节点不推进），0 可选 */
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

    public String getItemKey() {
        return itemKey;
    }

    public void setItemKey(String itemKey) {
        this.itemKey = itemKey;
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
