package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;

/**
 * 会话出网视图（api.md §1.4 SessionVO）。
 *
 * @param id            会话 ID
 * @param requirementId 归属需求
 * @param dshSessionId  DSH 会话标识
 * @param title         标题，可空
 * @param lastActiveAt  最近活跃时间
 * @param archived      是否归档
 * @param isNodePrimary 是否某节点的默认会话
 */
public record SessionVO(
        Long id,
        Long requirementId,
        String dshSessionId,
        String title,

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime lastActiveAt,

        Boolean archived,
        Boolean isNodePrimary) {

    /**
     * 由 service 视图转换。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static SessionVO of(com.fde.workbench.service.vo.SessionVO source) {
        return new SessionVO(source.id(), source.requirementId(), source.dshSessionId(), source.title(),
                toLocal(source.lastActiveAt()), source.archived(), source.isNodePrimary());
    }

    private static LocalDateTime toLocal(java.time.OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
