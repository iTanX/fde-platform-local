package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;

/**
 * 节点输入上传结果出网视图（api.md §8 POST 上传输入）。
 *
 * @param fileId        文件 ID
 * @param originalName  原始文件名
 * @param sizeBytes     大小
 * @param inputRecordId 输入记录 ID（删除该输入时用）
 * @param inputItemId   归属固定输入项 ID（自由输入为 null）
 * @param uploadedAt    上传时间
 */
public record NodeInputUploadVO(
        Long fileId,
        String originalName,
        Long sizeBytes,
        Long inputRecordId,
        Long inputItemId,

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime uploadedAt) {

    /**
     * 由 service 视图转换。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static NodeInputUploadVO of(com.fde.workbench.service.vo.NodeInputUploadVO source) {
        return new NodeInputUploadVO(source.fileId(), source.originalName(), source.sizeBytes(),
                source.inputRecordId(), source.inputItemId(),
                source.uploadedAt() == null ? null : source.uploadedAt().toLocalDateTime());
    }
}
