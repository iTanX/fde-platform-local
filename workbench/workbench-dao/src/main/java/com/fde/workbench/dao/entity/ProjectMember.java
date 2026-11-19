package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 项目成员实体，对应表 {@code wb_project_member}（docs/database/001_init.sql §3）。
 * <p>
 * {@code role} 取值 {@code owner} / {@code member}；一个项目有且只有一个 owner。
 */
public class ProjectMember {

    /** 成员记录主键 */
    private Long id;

    /** 项目 ID */
    private Long projectId;

    /** 用户 ID */
    private Long userId;

    /** 角色：owner 或 member */
    private String role;

    /** 加入时间（应用写入） */
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
