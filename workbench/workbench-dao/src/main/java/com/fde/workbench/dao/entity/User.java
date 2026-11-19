package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 {@code wb_user}（docs/database/001_init.sql §1）。
 * <p>
 * 注意：注册允许空密码，此时 {@code password_hash} 为 {@code null}（表结构事实源已约定，不写空串哈希）。
 */
public class User {

    /** 用户主键 */
    private Long id;

    /** 登录用户名，唯一 */
    private String username;

    /** BCrypt 哈希；注册时密码为空则为 null */
    private String passwordHash;

    /** 展示名称，注册时默认等于用户名 */
    private String displayName;

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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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
