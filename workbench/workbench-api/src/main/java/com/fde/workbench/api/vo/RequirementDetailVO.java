package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 需求详情（api 层出网视图，docs/api.md §7 {@code GET /api/requirements/{id}}）。
 * <p>
 * 结构与 service 层的 {@code RequirementDetailVO} 完全一致（字段名、顺序），单独再声明一次的原因
 * 是 api 层与 service 层的时间序列化器不同：service 用 {@code ServiceTimeFormats}，出网必须换成
 * api 层的 {@link TimeFormats}，保证 JSON 里时间带 {@code +08:00} 偏移。Controller/DTO/VO 分离的
 * 约定是"出网结构在 api 层定型"。
 *
 * @param requirement 需求本体
 * @param nodes       节点实例进度（按 seq 升序，只含进度字段，明细属 T5-1）
 * @param permissions 当前登录人的操作权限
 */
public record RequirementDetailVO(
        RequirementVO requirement,
        List<NodeProgressVO> nodes,
        PermissionsVO permissions) {

    /**
     * 节点进度项。
     *
     * @param id          节点实例 ID（对外即 nodeId）
     * @param seq         执行顺序
     * @param name        快照名称
     * @param status      waiting / current / done
     * @param startedAt   成为当前节点的时间，可空
     * @param completedAt 完成时间，可空
     */
    public record NodeProgressVO(
            Long id,
            Integer seq,
            String name,
            String status,

            @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
            LocalDateTime startedAt,

            @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
            LocalDateTime completedAt) {
    }

    /**
     * 权限视图。
     *
     * @param canOperate 能否操作：创建人或 owner，且需求 status=in_progress
     * @param isCreator  当前登录人是否是需求创建人
     */
    public record PermissionsVO(Boolean canOperate, Boolean isCreator) {
    }

    /**
     * 由 service 视图转换（时间字段从带偏移的时间还原成库里的 +08:00 墙钟时间）。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static RequirementDetailVO of(com.fde.workbench.service.vo.RequirementDetailVO source) {
        List<NodeProgressVO> nodes = source.nodes().stream()
                .map(node -> new NodeProgressVO(node.id(), node.seq(), node.name(), node.status(),
                        toLocal(node.startedAt()), toLocal(node.completedAt())))
                .toList();
        return new RequirementDetailVO(
                RequirementVO.of(source.requirement()),
                nodes,
                new PermissionsVO(source.permissions().canOperate(), source.permissions().isCreator()));
    }

    private static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
