package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 节点输出项记录，对应表 {@code wb_requirement_node_output}（docs/database/001_init.sql §7、
 * docs/database.md §5.4）。
 * <p>
 * {@code UNIQUE KEY uk_node_output_item(node_instance_id, item_key)} 保证"一个输出项只有一个当前版本"；
 * 重新提交时 {@code version_no + 1} 并改指向新的 {@code file_id}，<b>旧 {@code wb_file} 行保留</b>
 * （审计与历史版本用，页面默认只展示当前版本）。
 * <p>
 * 本实体同时承载查询时 join {@code wb_file} 得到的展示字段（{@code original_name / size_bytes}），
 * 让"节点信息页的输出项"一次查询拿全。
 */
public class NodeOutputRecord {

    /** 主键 */
    private Long id;

    /** 节点实例 ID */
    private Long nodeInstanceId;

    /** 输出项来源 ID（流程节点上的输出项，快照值） */
    private Long outputItemId;

    /** 输出项稳定键（模型工具按它提交） */
    private String itemKey;

    /** 当前文件登记 ID */
    private Long fileId;

    /** 当前版本号，从 1 开始，重新提交 +1 */
    private Integer versionNo;

    /** 提交人 */
    private Long committedBy;

    /** 提交时间 */
    private LocalDateTime committedAt;

    // ---- 以下为查询时 join wb_file 得到的展示字段，不参与写入 ----

    /** 当前文件名 */
    private String originalName;

    /** 当前文件大小 */
    private Long sizeBytes;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getNodeInstanceId() {
        return nodeInstanceId;
    }

    public void setNodeInstanceId(Long nodeInstanceId) {
        this.nodeInstanceId = nodeInstanceId;
    }

    public Long getOutputItemId() {
        return outputItemId;
    }

    public void setOutputItemId(Long outputItemId) {
        this.outputItemId = outputItemId;
    }

    public String getItemKey() {
        return itemKey;
    }

    public void setItemKey(String itemKey) {
        this.itemKey = itemKey;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public Long getCommittedBy() {
        return committedBy;
    }

    public void setCommittedBy(Long committedBy) {
        this.committedBy = committedBy;
    }

    public LocalDateTime getCommittedAt() {
        return committedAt;
    }

    public void setCommittedAt(LocalDateTime committedAt) {
        this.committedAt = committedAt;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}
