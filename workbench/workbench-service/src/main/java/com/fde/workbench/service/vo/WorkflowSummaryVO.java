package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.dao.entity.WorkflowWithStats;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 流程视图对象（docs/api.md §1.4 WorkflowVO，列表接口使用）。
 * <p>
 * {@code revision} 的取值方式见 {@link WorkflowVO}：本表没有 revision 列，
 * 这里枚举的是 {@code updated_at} 的毫秒时间戳（{@code 1970-01-01T00:00:00Z} 起的毫秒数）。
 * 列表不带 {@code nodes}：列表页只需要名称、说明、节点数与版本号，节点由详情接口按需取。
 *
 * @param id          流程 ID
 * @param projectId   所属项目 ID
 * @param name        流程名称
 * @param description 流程说明，可空
 * @param nodeCount   节点数
 * @param revision    并发版本号（= updated_at 毫秒时间戳）
 * @param createdBy   创建人
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record WorkflowSummaryVO(
        Long id,
        Long projectId,
        String name,
        String description,
        Integer nodeCount,
        RevisionStamp revision,
        Long createdBy,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime createdAt,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime updatedAt) {

    /**
     * 由查询投影转换。
     *
     * @param workflow 流程 + 节点数
     * @return 视图对象
     */
    public static WorkflowSummaryVO of(WorkflowWithStats workflow) {
        return new WorkflowSummaryVO(
                workflow.getId(),
                workflow.getProjectId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getNodeCount() == null ? 0 : workflow.getNodeCount(),
                RevisionStamp.of(workflow.getUpdatedAt()),
                workflow.getCreatedBy(),
                toOffset(workflow.getCreatedAt()),
                toOffset(workflow.getUpdatedAt()));
    }

    /**
     * 库中 {@code +08:00} 墙钟时间 → 带偏移的时间（服务层 VO 统一输出带偏移的绝对时间）。
     *
     * @param time 墙钟时间
     * @return 带 +08:00 偏移的时间；null 透传
     */
    private static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
    }
}
