package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.dao.entity.ProjectWithStats;

import java.time.LocalDateTime;

/**
 * 项目视图对象（docs/api.md §1.4 ProjectVO）。
 * <p>
 * 时间字段以 {@code yyyy-MM-dd'T'HH:mm:ssXXX}（+08:00）输出；{@code revision} 供前端编辑表单回传做乐观锁。
 *
 * @param id               项目 ID
 * @param name             项目名称
 * @param description      项目简介
 * @param ownerId          owner 用户 ID
 * @param memberCount      成员数
 * @param requirementCount 需求数
 * @param revision         乐观锁版本号
 * @param createdAt        创建时间
 */
public record ProjectVO(
        Long id,
        String name,
        String description,
        Long ownerId,
        Integer memberCount,
        Integer requirementCount,
        Integer revision,

        // 输出形如 2026-11-19T10:00:00+08:00（api.md §1.4 示例）
        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime createdAt) {

    /**
     * 由项目实体（含统计列）转换。
     *
     * @param project 项目实体
     * @return 视图对象
     */
    public static ProjectVO of(ProjectWithStats project) {
        return new ProjectVO(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getOwnerId(),
                project.getMemberCount() == null ? 0 : project.getMemberCount(),
                project.getRequirementCount() == null ? 0 : project.getRequirementCount(),
                project.getRevision(),
                project.getCreatedAt());
    }
}
