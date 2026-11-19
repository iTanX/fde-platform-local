package com.fde.workbench.dao.entity;

import java.time.LocalDateTime;

/**
 * 需求实体，对应表 {@code wb_requirement}（docs/database/001_init.sql §6，docs/api.md §7）。
 * <p>
 * 需求 = 一次"流程实例"：创建时绑定一个流程（创建后不可更换，PRD AC-F2.1），并按该流程的节点
 * 生成 {@link RequirementNode} 实例（PRD AC-F1.1）。{@code current_seq} 指向当前正在执行的节点，
 * 与 {@code wb_requirement_node.seq} 对应（docs/database.md §5.3）。
 * <p>
 * <b>{@code revision} 与流程不同，是本表自带的 INT 列</b>：每次编辑 +1，编辑 / 删除时以
 * {@code WHERE revision = 期望值} 做比较并交换，影响 0 行即 {@code 409/10402}。
 * 它对外就是 api.md §1.4 里的小整数（从 1 开始），前端原样回传即可。
 * <p>
 * 删除是<b>物理删除</b>（本表没有 {@code deleted} 列）：节点实例、节点输入 / 输出、会话等关联数据
 * 必须在同一事务内按外键顺序显式清理（见 {@code RequirementService#delete}）。
 */
public class Requirement {

    /** 需求状态：进行中（与 wb_requirement.status 注释一致） */
    public static final String STATUS_IN_PROGRESS = "in_progress";

    /** 需求状态：已完成 */
    public static final String STATUS_COMPLETED = "completed";

    /** 需求主键 */
    private Long id;

    /** 所属项目 ID */
    private Long projectId;

    /** 绑定的流程 ID；创建后不可更换（PRD AC-F2.1），已有需求只用于追溯来源 */
    private Long workflowId;

    /** 需求标题（1–200 字符，与列 VARCHAR(200) 一致） */
    private String title;

    /** 需求说明，可空 */
    private String description;

    /** 创建人；创建人或项目 owner 才能编辑 / 删除 / 推进（权限记号 C） */
    private Long creatorId;

    /** 状态：{@code in_progress} / {@code completed} */
    private String status;

    /** 当前节点序号（对应 wb_requirement_node.seq） */
    private Integer currentSeq;

    /** 该需求的 DSH workspace 绝对路径（数据库只存绝对路径） */
    private String dshWorkspacePath;

    /** 乐观锁版本号：每次编辑 +1（对外即 api.md 的 revision） */
    private Integer revision;

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

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCurrentSeq() {
        return currentSeq;
    }

    public void setCurrentSeq(Integer currentSeq) {
        this.currentSeq = currentSeq;
    }

    public String getDshWorkspacePath() {
        return dshWorkspacePath;
    }

    public void setDshWorkspacePath(String dshWorkspacePath) {
        this.dshWorkspacePath = dshWorkspacePath;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
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
