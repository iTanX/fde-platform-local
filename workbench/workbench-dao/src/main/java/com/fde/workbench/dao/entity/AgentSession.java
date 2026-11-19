package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 需求会话实体，对应表 {@code wb_agent_session}（docs/database/001_init.sql §8、PRD REQ-X1/X2）。
 * <p>
 * <b>会话归属需求，不归属节点</b>：一个需求可有多个会话；节点与会话只是展示用弱关联
 * （{@code wb_node_session_link}）加"默认会话"指针（{@code wb_requirement_node.primary_session_id}）。
 * 因此 {@code dsh_session_id} 全局唯一（{@code uk_agent_session_dsh}），跨需求也不能重复。
 * <p>
 * {@code dsh_workspace_path} 冗余保存需求 workspace 绝对路径：会话恢复时不必再回查需求行，
 * 也便于排查"会话所在目录与需求 workspace 不一致"的异常数据。
 */
public class AgentSession {

    /** 主键（对外即 api.md 的 sessionId） */
    private Long id;

    /** 归属需求 */
    private Long requirementId;

    /** DSH 会话标识（会话恢复的唯一依据） */
    private String dshSessionId;

    /** 会话所在 workspace（= 需求 workspace 绝对路径） */
    private String dshWorkspacePath;

    /** 会话标题，可空 */
    private String title;

    /** 创建人 */
    private Long createdBy;

    /** 最近活跃时间（默认会话解析的排序键） */
    private LocalDateTime lastActiveAt;

    /** 1 = 已归档，不再作为默认会话 */
    private Integer archived;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(Long requirementId) {
        this.requirementId = requirementId;
    }

    public String getDshSessionId() {
        return dshSessionId;
    }

    public void setDshSessionId(String dshSessionId) {
        this.dshSessionId = dshSessionId;
    }

    public String getDshWorkspacePath() {
        return dshWorkspacePath;
    }

    public void setDshWorkspacePath(String dshWorkspacePath) {
        this.dshWorkspacePath = dshWorkspacePath;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(LocalDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Integer getArchived() {
        return archived;
    }

    public void setArchived(Integer archived) {
        this.archived = archived;
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
