package com.fde.workbench.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 添加成员请求体（docs/api.md §4 POST /api/projects/{projectId}/members）。
 * <p>
 * 只接受用户名：成员关系最终落库为用户 ID，由 Service 按用户名解析。
 *
 * @param username 目标用户登录名（必填，禁止全空白，≤64 字符以匹配 wb_user.username）
 */
public record AddMemberRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名不能超过 64 个字符")
        String username) {
}
