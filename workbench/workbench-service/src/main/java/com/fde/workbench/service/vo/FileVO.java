package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 文件元信息视图（docs/api.md §10 {@code GET /api/files/{fileId}}）。
 *
 * @param id           文件 ID
 * @param projectId    所属项目
 * @param requirementId 所属需求，可空（技能包文件为 null）
 * @param refType      node_input / node_output / skill_file
 * @param originalName 原始文件名
 * @param mimeType     MIME 类型，可空
 * @param sizeBytes    大小（字节）
 * @param sha256       内容摘要
 * @param uploadedBy   上传 / 登记人
 * @param uploadedAt   上传 / 登记时间
 * @param previewable  是否支持预览（md / txt / 常见图片）
 */
public record FileVO(
        Long id,
        Long projectId,
        Long requirementId,
        String refType,
        String originalName,
        String mimeType,
        Long sizeBytes,
        String sha256,
        Long uploadedBy,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime uploadedAt,

        Boolean previewable) {

    /** 库中 +08:00 墙钟时间 → 带偏移时间；null 透传 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
    }
}
