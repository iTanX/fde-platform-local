package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.entity.RequirementWithStats;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 需求视图对象（docs/api.md §1.4 RequirementVO，列表项与详情里的 {@code requirement} 共用）。
 * <p>
 * {@code revision} 是本表自带的 INT 列（从 1 开始，每次编辑 +1），与流程的"毫秒时间戳 revision"
 * 不同——需求表本来就有 revision 列，直接用即可，前端同样只需原样回传。
 * <p>
 * {@code editable} 表示"当前登录人此刻能不能编辑 / 删除该需求"（创建人或 owner）。
 * 它与 {@code permissions.canOperate} 的区别：{@code editable} 只看权限，不看流程状态；
 * {@code canOperate} 还要求需求仍在进行中。列表页用它决定是否给出"编辑"入口。
 *
 * @param id               需求 ID
 * @param projectId        所属项目 ID
 * @param workflowId       绑定的流程 ID（不可更换）
 * @param workflowName     流程名称（流程已逻辑删除时仍返回来源名称，可空）
 * @param title            标题
 * @param description      说明，可空
 * @param creatorId        创建人
 * @param status           状态 {@code in_progress} / {@code completed}
 * @param currentSeq       当前节点序号
 * @param nodeCount        节点实例数（快照）
 * @param doneNodeCount    已完成节点实例数
 * @param revision         乐观锁版本号（INT）
 * @param editable         当前登录人能否编辑 / 删除
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 */
public record RequirementVO(
        Long id,
        Long projectId,
        Long workflowId,
        String workflowName,
        String title,
        String description,
        Long creatorId,
        String status,
        Integer currentSeq,
        Integer nodeCount,
        Integer doneNodeCount,
        Integer revision,
        Boolean editable,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime createdAt,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime updatedAt) {

    /**
     * 由查询投影转换。
     *
     * @param requirement 需求 + 流程名称 + 节点统计
     * @param editable    当前登录人能否编辑 / 删除
     * @return 视图对象
     */
    public static RequirementVO of(RequirementWithStats requirement, boolean editable) {
        return new RequirementVO(
                requirement.getId(),
                requirement.getProjectId(),
                requirement.getWorkflowId(),
                requirement.getWorkflowName(),
                requirement.getTitle(),
                requirement.getDescription(),
                requirement.getCreatorId(),
                requirement.getStatus(),
                requirement.getCurrentSeq(),
                requirement.getNodeCount() == null ? 0 : requirement.getNodeCount(),
                requirement.getDoneNodeCount() == null ? 0 : requirement.getDoneNodeCount(),
                requirement.getRevision(),
                editable,
                toOffset(requirement.getCreatedAt()),
                toOffset(requirement.getUpdatedAt()));
    }

    /**
     * 库中 {@code +08:00} 墙钟时间 → 带偏移的时间。
     *
     * @param time 墙钟时间
     * @return 带 +08:00 偏移的时间；null 透传
     */
    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
    }
}
