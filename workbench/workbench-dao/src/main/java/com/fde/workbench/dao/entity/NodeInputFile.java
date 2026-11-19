package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 节点输入文件记录，对应表 {@code wb_requirement_node_input}（docs/database/001_init.sql §7）。
 * <p>
 * <b>归属关系</b>：一行 = "某个节点实例收到了某个文件"。
 * {@code input_item_id} 为固定输入项 ID（{@code input_type=fixed}），
 * 自由输入（{@code input_type=free}）时恒为 NULL——这正是推进判定里"free 节点至少一个输入文件"
 * 的依据（docs/database.md §5.3）。同一固定输入项允许多次上传（多个文件累加，不覆盖）。
 * <p>
 * 查询类接口直接复用这里的 join 列（{@code original_name / size_bytes / sha256 / storage_rel_path}），
 * 避免"先查记录再逐条查文件"的 N+1。
 */
public class NodeInputFile {

    /** 输入记录主键（对外即 api.md 的 inputRecordId） */
    private Long id;

    /** 节点实例 ID */
    private Long nodeInstanceId;

    /** 固定输入项 ID；自由输入为 NULL */
    private Long inputItemId;

    /** 文件登记 ID（{@code wb_file.id}） */
    private Long fileId;

    /** 上传人 */
    private Long uploadedBy;

    /** 创建时间（应用写入） */
    private LocalDateTime createdAt;

    // ---- 以下为查询时 join wb_file 得到的展示字段，不参与写入 ----

    /** 原始文件名 */
    private String originalName;

    /** 大小（字节） */
    private Long sizeBytes;

    /** 内容摘要 */
    private String sha256;

    /** 相对存储根的路径 */
    private String storageRelPath;

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

    public Long getInputItemId() {
        return inputItemId;
    }

    public void setInputItemId(Long inputItemId) {
        this.inputItemId = inputItemId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStorageRelPath() {
        return storageRelPath;
    }

    public void setStorageRelPath(String storageRelPath) {
        this.storageRelPath = storageRelPath;
    }
}
