package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.dao.entity.SkillWithStats;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;

/**
 * 技能视图对象（docs/api.md §1.4 SkillVO）。
 * <p>
 * 只暴露页面需要的字段：{@code currentVersionId}/{@code currentVersionNo} 决定"节点默认用哪一版"，
 * {@code usageCount} 让前端在删除前提示"被 N 个流程节点引用"。
 * 时间字段以 +08:00 偏移输出（api.md §1.5）。
 *
 * @param id               技能 ID
 * @param name             技能名称
 * @param description      技能说明（取自 SKILL.md frontmatter），可空
 * @param currentVersionId 当前版本 ID
 * @param currentVersionNo 当前版本号
 * @param usageCount       被流程节点引用的次数
 * @param createdBy        创建人（上传第一版的人）
 * @param createdAt        创建时间
 */
public record SkillVO(
        Long id,
        String name,
        String description,
        Long currentVersionId,
        Integer currentVersionNo,
        Integer usageCount,
        Long createdBy,

        // 输出形如 2026-11-19T10:00:00+08:00
        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        LocalDateTime createdAt) {

    /**
     * 由查询投影转换。
     *
     * @param skill 技能 + 统计列
     * @return 视图对象
     */
    public static SkillVO of(SkillWithStats skill) {
        return new SkillVO(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getCurrentVersionId(),
                skill.getCurrentVersionNo(),
                skill.getUsageCount() == null ? 0 : skill.getUsageCount(),
                skill.getCreatedBy(),
                skill.getCreatedAt());
    }
}
