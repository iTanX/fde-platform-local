package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 技能实体，对应表 {@code wb_skill}（docs/database/001_init.sql §4）。
 * <p>
 * 技能是"版本容器"：名称与说明在技能行上，文件内容在 {@code wb_skill_version} 上；
 * {@code current_version_id} 指向当前生效版本（上传新版本后指向最新版本，PRD AC-D1.2）。
 * 删除为逻辑删除（{@code deleted=1}），历史版本与历史需求快照仍可追溯。
 */
public class Skill {

    /** 技能主键 */
    private Long id;

    /** 所属项目 ID */
    private Long projectId;

    /** 技能显示名（同名上传视为同一技能的新版本） */
    private String name;

    /** 技能用途，取自 SKILL.md frontmatter 的 description，可空 */
    private String description;

    /** 当前生效版本 ID；技能刚创建、版本尚未回填时为 null */
    private Long currentVersionId;

    /** 创建人（上传第一版的用户），决定谁能删除该技能 */
    private Long createdBy;

    /** 逻辑删除：0 正常，1 已删除 */
    private Integer deleted;

    /** 创建时间（应用写入） */
    private LocalDateTime createdAt;

    /** 更新时间（应用写入；上传新版本时刷新） */
    private LocalDateTime updatedAt;

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

    public Long getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(Long currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
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
