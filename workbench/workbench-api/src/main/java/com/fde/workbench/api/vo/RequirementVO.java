package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 需求视图（api 层出网视图，docs/api.md §1.4 RequirementVO）：列表项与详情里的 {@code requirement} 共用。
 * <p>
 * {@code revision} 是需求表的 {@code revision INT} 原值（从 1 开始，每次编辑 +1），
 * 与流程接口的"毫秒时间戳 revision"不同——前端同样只需原样回传。
 *
 * @param id            需求 ID
 * @param projectId     所属项目 ID
 * @param workflowId    绑定的流程 ID（不可更换）
 * @param workflowName  流程名称（流程已逻辑删除时仍返回来源名称，可空）
 * @param title         标题
 * @param description   说明，可空
 * @param creatorId     创建人
 * @param status        状态 {@code in_progress} / {@code completed}
 * @param currentSeq    当前节点序号
 * @param nodeCount     节点实例数（快照）
 * @param doneNodeCount 已完成节点实例数
 * @param revision      乐观锁版本号
 * @param editable      当前登录人能否编辑 / 删除（创建人或 owner）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
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

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime createdAt,

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime updatedAt) {

    /**
     * 由 service 视图转换。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static RequirementVO of(com.fde.workbench.service.vo.RequirementVO source) {
        return new RequirementVO(
                source.id(),
                source.projectId(),
                source.workflowId(),
                source.workflowName(),
                source.title(),
                source.description(),
                source.creatorId(),
                source.status(),
                source.currentSeq(),
                source.nodeCount(),
                source.doneNodeCount(),
                source.revision(),
                source.editable(),
                toLocal(source.createdAt()),
                toLocal(source.updatedAt()));
    }

    /**
     * 列表整体转换。
     *
     * @param sources service 层视图列表
     * @return 出网视图列表
     */
    public static List<RequirementVO> of(List<com.fde.workbench.service.vo.RequirementVO> sources) {
        return sources.stream().map(RequirementVO::of).toList();
    }

    private static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
