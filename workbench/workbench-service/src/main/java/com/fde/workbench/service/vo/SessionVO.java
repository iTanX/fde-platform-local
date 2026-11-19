package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 会话视图（docs/api.md §1.4 SessionVO、§9 会话）。
 *
 * @param id            会话 ID
 * @param requirementId 归属需求
 * @param dshSessionId  DSH 会话标识（插件用它 {@code sessions.retain}）
 * @param title         会话标题，可空
 * @param lastActiveAt  最近活跃时间（列表按它倒序）
 * @param archived      是否已归档（归档后不再作为默认会话，但仍可查看）
 * @param isNodePrimary 是否被需求内某个节点记录为默认会话
 */
public record SessionVO(
        Long id,
        Long requirementId,
        String dshSessionId,
        String title,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime lastActiveAt,

        Boolean archived,
        Boolean isNodePrimary) {

    /** 库中 +08:00 墙钟时间 → 带偏移时间；null 透传 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
    }
}
