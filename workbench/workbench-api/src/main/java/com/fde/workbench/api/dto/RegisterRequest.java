package com.fde.workbench.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体（docs/api.md §2 POST /api/auth/register）。
 * <p>
 * 密码允许为空或省略：空密码表示该用户不设密码，只能以空密码登录。
 *
 * @param username    用户名，3–32 位字母、数字、下划线
 * @param password    密码，可为空
 * @param displayName 展示名，可空，默认等于用户名
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名须为 3–32 位字母、数字或下划线")
        String username,

        // 不做 NotBlank：密码可为空字符串或省略（api.md §2）
        @Size(max = 72, message = "密码长度不能超过 72 位")
        String password,

        @Size(max = 64, message = "展示名不能超过 64 个字符")
        String displayName) {
}
