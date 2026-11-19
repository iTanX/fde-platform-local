package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 节点输入上传结果（docs/api.md §8 POST 上传输入的成功响应：{@code {fileId, originalName, sizeBytes}}）。
 * <p>
 * 额外返回 {@code inputRecordId} 与 {@code inputItemId}：前者用于删除该输入
 * （{@code DELETE .../inputs/{inputRecordId}}），后者用于前端把文件挂到对应输入项下。
 *
 * @param fileId        文件 ID
 * @param originalName  原始文件名
 * @param sizeBytes     大小
 * @param inputRecordId 输入记录 ID
 * @param inputItemId   归属固定输入项 ID（自由输入为 null）
 * @param uploadedAt    上传时间
 */
public record NodeInputUploadVO(
        Long fileId,
        String originalName,
        Long sizeBytes,
        Long inputRecordId,
        Long inputItemId,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime uploadedAt) {

    /** 库中 +08:00 墙钟时间 → 带偏移时间；null 透传 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
    }
}
