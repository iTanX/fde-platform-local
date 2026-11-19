package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.dao.entity.Workflow;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 流程详情视图对象（docs/api.md §1.4 WorkflowVO + NodeVO，{@code GET /api/workflows/{id}}）。
 * <p>
 * <b>revision 的取舍（重要）</b>：{@code wb_workflow} 表没有 {@code revision} 列，本任务又禁止改表，
 * 因此这里把 {@code revision} 定义为 {@code updated_at} 的<b>毫秒时间戳</b>（UTC epoch millis）：
 * <ul>
 *   <li>单调、可比较：每次写流程（改名称、保存节点）都会把 {@code updated_at} 推到严格更大的值；</li>
 *   <li>无损往返：前端原样回传，后端能精确还原成 {@code DATETIME(3)} 做相等比较，不需要额外精度假设；</li>
 *   <li>对前端透明：前端只需"拿到 revision → 原样回传"，不必理解它到底是什么（见回传说明）。</li>
 * </ul>
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
 * @param nodes       节点列表（按 seq 升序）
 */
public record WorkflowVO(
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
        OffsetDateTime updatedAt,

        List<NodeVO> nodes) {

    /**
     * 由实体与已组装好的节点转换。
     *
     * @param workflow 流程实体
     * @param nodes    节点视图（按 seq 升序）
     * @return 视图对象
     */
    public static WorkflowVO of(Workflow workflow, List<NodeVO> nodes) {
        return new WorkflowVO(
                workflow.getId(),
                workflow.getProjectId(),
                workflow.getName(),
                workflow.getDescription(),
                nodes.size(),
                RevisionStamp.of(workflow.getUpdatedAt()),
                workflow.getCreatedBy(),
                toOffset(workflow.getCreatedAt()),
                toOffset(workflow.getUpdatedAt()),
                nodes);
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
