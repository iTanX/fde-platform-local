package com.fde.workbench.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建项目请求体（docs/api.md §3 POST /api/projects）。
 *
 * @param name        项目名称，1–128 字符（必填，禁止全空白）
 * @param description 项目简介，可空，≤1000 字符
 */
public record ProjectCreateRequest(
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称不能超过 128 个字符")
        String name,

        @Size(max = 1000, message = "项目简介不能超过 1000 个字符")
        String description) {
}
