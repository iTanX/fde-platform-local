package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.common.bo.RevisionStamp;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 流程列表项（api 层出网视图，docs/api.md §1.4 WorkflowVO 的列表形态）。
 * <p>
 * 为什么 service 模块已经有同名 VO 还要再建一个：api 层对外的时间格式由
 * {@link TimeFormats}（api 模块）负责，service 层用的是 {@code ServiceTimeFormats}，两者序列化器不同。
 * Controller/DTO/VO 分离的约定是"出网结构在 api 层定型"，因此这里重新声明 record 并把时间字段换成
 * api 层序列化器；字段名与顺序与 service VO 完全一致。
 * <p>
 * <b>revision</b>：本表没有 revision 列，其值是 {@code updated_at} 的毫秒时间戳（{@link RevisionStamp}），
 * 前端只需原样回传（详见 {@link WorkflowVO} 的说明）。
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

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime createdAt,

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime updatedAt) {

    /**
     * 由 service 视图转换。时间字段从 {@code +08:00} 的 OffsetDateTime 还原成 {@link LocalDateTime}
     * （库中时间本来就是 +08:00 墙钟时间），再交给 api 层序列化器输出，保证与 api.md 格式一致。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static WorkflowSummaryVO of(com.fde.workbench.service.vo.WorkflowSummaryVO source) {
        return new WorkflowSummaryVO(
                source.id(),
                source.projectId(),
                source.name(),
                source.description(),
                source.nodeCount(),
                source.revision(),
                source.createdBy(),
                toLocal(source.createdAt()),
                toLocal(source.updatedAt()));
    }

    /**
     * 列表整体转换。
     *
     * @param sources service 层视图列表
     * @return 出网视图列表
     */
    public static List<WorkflowSummaryVO> of(List<com.fde.workbench.service.vo.WorkflowSummaryVO> sources) {
        return sources.stream().map(WorkflowSummaryVO::of).toList();
    }

    private static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
