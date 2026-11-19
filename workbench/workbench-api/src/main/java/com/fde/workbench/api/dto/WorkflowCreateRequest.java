package com.fde.workbench.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建流程请求体（docs/api.md §6 {@code POST /api/projects/{projectId}/workflows}）。
 * <p>
 * 只收名称与说明：节点由"{@code PUT /api/workflows/{id}/nodes} 整体保存"写入，
 * 因此创建流程是一次轻量操作，前端可以先建空流程再进入节点编辑（PRD REQ-E1）。
 *
 * @param name        流程名称，1–128 字符
 * @param description 流程说明，可空，≤1000 字符
 */
public record WorkflowCreateRequest(
        @NotBlank(message = "流程名称不能为空")
        @Size(max = 128, message = "流程名称不能超过 128 个字符")
        String name,

        @Size(max = 1000, message = "流程说明不能超过 1000 个字符")
        String description) {
}
