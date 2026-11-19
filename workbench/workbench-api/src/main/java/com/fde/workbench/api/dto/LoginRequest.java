package com.fde.workbench.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体（docs/api.md §2 POST /api/auth/login）。
 * <p>
 * 密码同样不做非空校验：空密码注册的用户必须能用空密码（或省略字段）登录（PRD AC-H1.3）。
 *
 * @param username 用户名
 * @param password 密码，可为空
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        String password) {
}
