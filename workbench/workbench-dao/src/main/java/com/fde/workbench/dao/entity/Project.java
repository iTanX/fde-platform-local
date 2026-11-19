package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 项目实体，对应表 {@code wb_project}（docs/database/001_init.sql §2）。
 * <p>
 * 可编辑聚合根：{@code revision} 为乐观锁版本号，每次修改 +1；{@code deleted} 为逻辑删除标记。
 */
public class Project {

    /** 项目主键 */
    private Long id;

    /** 项目名称（1–128 字符） */
    private String name;

    /** 项目简介，可空 */
    private String description;

    /** owner 用户 ID，与 wb_project_member 中 role=owner 的记录一致 */
    private Long ownerId;

    /** 乐观锁版本号，从 1 开始 */
    private Integer revision;

    /** 逻辑删除：0 正常，1 已删除 */
    private Integer deleted;

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

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
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
