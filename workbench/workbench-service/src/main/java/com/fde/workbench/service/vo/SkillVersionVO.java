package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.dao.entity.SkillVersion;
import com.fde.workbench.service.ServiceTimeFormats;
import com.fde.workbench.service.SkillPackageExtractor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能版本视图对象（docs/api.md §5 GET /api/skills/{skillId}/versions）。
 * <p>
 * {@code files} 是把库里的 {@code files_json} 解析成结构化清单后的结果，前端版本抽屉
 * 直接按它渲染"这一版包含哪些文件、多大"，不需要再单独调接口。
 *
 * @param id        版本 ID
 * @param versionNo 版本号（从 1 开始）
 * @param entryPath 入口文件相对路径（SKILL.md 或 skills/&lt;name&gt;/SKILL.md）
 * @param checksum  整体摘要
 * @param uploadedBy 上传人
 * @param createdAt 上传时间
 * @param files     文件清单（path / size / sha256），按路径升序
 */
public record SkillVersionVO(
        Long id,
        Integer versionNo,
        String entryPath,
        String checksum,
        Long uploadedBy,

        // 输出形如 2026-11-19T10:00:00+08:00
        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        LocalDateTime createdAt,
        List<SkillPackageExtractor.ExtractResult.FileEntry> files) {

    /**
     * 由版本实体 + 已解析的文件清单转换。
     *
     * @param version 版本实体
     * @param files   文件清单
     * @return 视图对象
     */
    public static SkillVersionVO of(SkillVersion version, List<SkillPackageExtractor.ExtractResult.FileEntry> files) {
        return new SkillVersionVO(
                version.getId(),
                version.getVersionNo(),
                version.getEntryPath(),
                version.getChecksum(),
                version.getUploadedBy(),
                version.getCreatedAt(),
                files);
    }
}
