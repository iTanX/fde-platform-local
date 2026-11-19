package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 流程实体，对应表 {@code wb_workflow}（docs/database/001_init.sql §5，docs/api.md §6）。
 * <p>
 * 流程是"需求模板"：需求创建时把节点、输入项、输出项、技能整体快照到
 * {@code wb_requirement_node}（PRD AC-F2.2），因此之后修改流程只影响新建需求。
 * <p>
 * 删除为逻辑删除（{@code deleted=1}）：被需求引用时禁止删除（{@code wb_requirement.workflow_id}）。
 * <p>
 * <b>为什么没有 revision 列</b>：本表在 001_init.sql 中没有乐观锁版本列，且本任务禁止改表结构，
 * 因此并发比较基准改用 {@code updated_at}（毫秒精度），详见
 * {@code com.fde.workbench.service.WorkflowService} 中关于 revision 取舍的说明。
 */
public class Workflow {

    /** 流程主键 */
    private Long id;

    /** 所属项目 ID */
    private Long projectId;

    /** 流程名称（1–128 字符） */
    private String name;

    /** 流程说明，可空（≤1000 字符） */
    private String description;

    /** 创建人；决定谁能编辑 / 删除该流程（权限记号 C） */
    private Long createdBy;

    /** 逻辑删除：0 正常，1 已删除 */
    private Integer deleted;

    /** 创建时间（应用写入） */
    private LocalDateTime createdAt;

    /** 更新时间（应用写入）；本表没有 revision 列，它同时充当乐观锁比较基准 */
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
