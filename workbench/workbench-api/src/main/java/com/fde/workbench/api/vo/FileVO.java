package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;

/**
 * 文件元信息出网视图（api.md §10 {@code GET /api/files/{fileId}}）。
 *
 * @param id            文件 ID
 * @param projectId     所属项目
 * @param requirementId 所属需求，可空
 * @param refType       引用类型
 * @param originalName  原始文件名
 * @param mimeType      MIME，可空
 * @param sizeBytes     大小
 * @param sha256        内容摘要
 * @param uploadedBy    登记人
 * @param uploadedAt    登记时间
 * @param previewable   是否可预览
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

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime uploadedAt,

        Boolean previewable) {

    /**
     * 由 service 视图转换。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static FileVO of(com.fde.workbench.service.vo.FileVO source) {
        return new FileVO(source.id(), source.projectId(), source.requirementId(), source.refType(),
                source.originalName(), source.mimeType(), source.sizeBytes(), source.sha256(),
                source.uploadedBy(),
                source.uploadedAt() == null ? null : source.uploadedAt().toLocalDateTime(),
                source.previewable());
    }
}
