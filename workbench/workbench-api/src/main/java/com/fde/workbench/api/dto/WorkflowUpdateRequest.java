package com.fde.workbench.api.dto;

import com.fde.workbench.common.bo.RevisionStamp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新流程名称 / 说明请求体（docs/api.md §6 {@code PUT /api/workflows/{id}}）。
 * <p>
 * {@code revision} 必填：本表没有 revision 列，其值由详情接口返回（= {@code updated_at} 毫秒时间戳），
 * 缺失会让后端无法判断"是不是基于最新版本提交的"，因此直接 400/10001 而不是静默放行。
 *
 * @param name        流程名称，1–128 字符
 * @param description 流程说明，可空，≤1000 字符
 * @param revision    前端持有的版本号（必填，JSON 整数）
 */
public record WorkflowUpdateRequest(
        @NotBlank(message = "流程名称不能为空")
        @Size(max = 128, message = "流程名称不能超过 128 个字符")
        String name,

        @Size(max = 1000, message = "流程说明不能超过 1000 个字符")
        String description,

        @NotNull(message = "缺少 revision（请先获取流程详情，再带回其 revision）")
        RevisionStamp revision) {
}
