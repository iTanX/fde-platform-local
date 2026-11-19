package com.fde.workbench.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑项目请求体（docs/api.md §3 PUT /api/projects/{id}）。
 * <p>
 * {@code revision} 必填：前端必须回传读取表单时的版本号，后端据此做乐观锁，冲突返回 409/10402。
 *
 * @param name        项目名称，1–128 字符
 * @param description 项目简介，可空
 * @param revision    乐观锁版本号，必填且 ≥1
 */
public record ProjectUpdateRequest(
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称不能超过 128 个字符")
        String name,

        @Size(max = 1000, message = "项目简介不能超过 1000 个字符")
        String description,

        @NotNull(message = "revision 不能为空")
        @Min(value = 1, message = "revision 必须大于 0")
        Integer revision) {
}
