package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 技能版本实体，对应表 {@code wb_skill_version}（docs/database/001_init.sql §4）。
 * <p>
 * 同一技能内 {@code version_no} 从 1 递增（唯一键 {@code uk_skill_version}）；
 * {@code files_json} 是解包后的文件清单，{@code checksum} 是整体内容摘要，两者共同决定
 * "下载时重打包哪些文件、内容是否被改动"。版本行只追加、不更新，历史版本永远可下载。
 */
public class SkillVersion {

    /** 版本主键 */
    private Long id;

    /** 所属技能 ID */
    private Long skillId;

    /** 版本号，同一技能内从 1 递增 */
    private Integer versionNo;

    /**
     * 文件清单 JSON 字符串，形如
     * {@code [{"path":"SKILL.md","size":123,"sha256":"..."}]}。
     * 以字符串出入库，实体层不做解析（解析与校验放在 Service）。
     */
    private String filesJson;

    /** 整体摘要：各文件 sha256 排序后再摘要，用于"同一内容重复上传"的比对 */
    private String checksum;

    /** 入口文件相对路径（固定 SKILL.md 或 skills/<name>/SKILL.md） */
    private String entryPath;

    /** 上传人 */
    private Long uploadedBy;

    /** 上传时间（应用写入） */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getFilesJson() {
        return filesJson;
    }

    public void setFilesJson(String filesJson) {
        this.filesJson = filesJson;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getEntryPath() {
        return entryPath;
    }

    public void setEntryPath(String entryPath) {
        this.entryPath = entryPath;
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
