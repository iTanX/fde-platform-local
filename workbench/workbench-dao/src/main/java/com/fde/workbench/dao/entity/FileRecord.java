package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 文件登记实体，对应表 {@code wb_file}（docs/database/001_init.sql §7、docs/database.md §2 原则 4）。
 * <p>
 * <b>文件内容与登记分离</b>：文件本体在存储根下的某个相对路径，本行只保存
 * {@code storage_rel_path} 与 {@code sha256}。因此"页面看到文件"的唯一依据是这里有一行登记，
 * 而不是磁盘上存在某个文件（plugin-integration.md §7：页面不扫描文件系统）。
 * <p>
 * {@code ref_type} 取值：
 * <ul>
 *   <li>{@code node_input}：节点输入（{@code ref_id} = {@code wb_requirement_node_input.id}）；</li>
 *   <li>{@code node_output}：节点输出（{@code ref_id} = {@code wb_requirement_node_output.id}，
 *       重新提交时旧行保留、新行指向新文件，见 docs/database.md §5.4）。</li>
 * </ul>
 * {@code ref_id} 在插入时还不知道（子表自增 ID），由 Service 在同一事务内回填。
 */
public class FileRecord {

    /** 文件类型：节点输入 */
    public static final String REF_TYPE_NODE_INPUT = "node_input";

    /** 文件类型：节点输出 */
    public static final String REF_TYPE_NODE_OUTPUT = "node_output";

    /** 主键 */
    private Long id;

    /** 所属项目（权限判定用） */
    private Long projectId;

    /** 归属需求；技能包文件为 NULL */
    private Long requirementId;

    /** 业务引用类型：skill_file / node_input / node_output / free_input */
    private String refType;

    /** 对应业务记录 ID */
    private Long refId;

    /** 原始文件名（展示与下载名） */
    private String originalName;

    /** 相对存储根的路径（{@code /} 分隔） */
    private String storageRelPath;

    /** MIME 类型 */
    private String mimeType;

    /** 大小（字节） */
    private Long sizeBytes;

    /** 内容摘要（SHA-256，小写十六进制） */
    private String sha256;

    /** 上传 / 写入登记的用户 */
    private Long uploadedBy;

    /** 创建时间（应用写入） */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public String getRefType() {
        return refType;
    }

    public void setRefType(String refType) {
        this.refType = refType;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getStorageRelPath() {
        return storageRelPath;
    }

    public void setStorageRelPath(String storageRelPath) {
        this.storageRelPath = storageRelPath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
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
}
