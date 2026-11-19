package com.fde.workbench.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建需求请求体（docs/api.md §7 {@code POST /api/projects/{projectId}/requirements}）。
 * <pre>
 * { "title": "客户工单分诊需求", "description": "…", "workflowId": 1 }
 * </pre>
 * {@code workflowId} 必填（api.md 原文"必须带 workflowId"）：需求创建即启动流程实例，
 * 没有流程就没有节点，也就无法确定"当前节点"（PRD AC-F1.1）。
 *
 * @param title       标题，1–200 字符（与 wb_requirement.title VARCHAR(200) 一致）
 * @param description 说明，可空
 * @param workflowId  绑定的流程 ID（必须是本项目内未删除、且至少有 1 个节点的流程）
 */
public record RequirementCreateRequest(
        @NotBlank(message = "需求标题不能为空")
        @Size(max = 200, message = "需求标题不能超过 200 个字符")
        String title,

        String description,

        @NotNull(message = "创建需求必须选择一个流程（workflowId）")
        Long workflowId) {
}
