package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.dao.entity.ProjectMemberDetail;

import java.time.LocalDateTime;

/**
 * 成员视图对象（docs/api.md §1.4 MemberVO）。
 * <p>
 * 只暴露成员关系与展示信息，不含 password_hash 等账号敏感字段。
 * {@code joinedAt} 即成员记录的 {@code created_at}，以 +08:00 偏移输出。
 *
 * @param userId      用户 ID
 * @param username    登录用户名
 * @param displayName 展示名称
 * @param role        角色：owner / member
 * @param joinedAt    加入时间
 */
public record MemberVO(
        Long userId,
        String username,
        String displayName,
        String role,

        // 输出形如 2026-11-19T10:00:00+08:00（api.md §1.4）
        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime joinedAt) {

    /**
     * 由查询投影转换。
     *
     * @param detail 成员 + 用户信息的查询结果
     * @return 视图对象
     */
    public static MemberVO of(ProjectMemberDetail detail) {
        return new MemberVO(
                detail.getUserId(),
                detail.getUsername(),
                detail.getDisplayName(),
                detail.getRole(),
                detail.getCreatedAt());
    }
}
