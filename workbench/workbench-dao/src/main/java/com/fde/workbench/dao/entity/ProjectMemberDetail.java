package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 成员列表项：{@code wb_project_member} 关联 {@code wb_user} 的查询结果，供成员列表接口使用。
 * <p>
 * 这是跨表投影（不同于 {@link ProjectMember} 的单表实体），只用于读；写操作仍走 {@link ProjectMember}。
 */
public class ProjectMemberDetail {

    /** 成员记录主键（wb_project_member.id） */
    private Long id;

    /** 项目 ID */
    private Long projectId;

    /** 用户 ID（即成员列表里的 userId） */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 展示名称 */
    private String displayName;

    /** 角色：owner 或 member */
    private String role;

    /** 加入时间 */
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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
