package com.fde.workbench.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记会话请求（api.md §9 {@code POST /api/requirements/{rid}/sessions}）。
 * <p>
 * 校验放在 DTO 上（Jakarta Validation，失败统一 400/10001）；"会话是否重复""节点是否属于该需求"
 * 这类需要查库的规则仍在 Service。
 *
 * @param dshSessionId DSH 会话标识（必填，≤128）
 * @param nodeId       绑定节点实例 ID（必填）
 * @param title        会话标题（可空，≤200）
 */
public record SessionCreateRequest(
        @NotBlank(message = "dshSessionId 不能为空")
        @Size(max = 128, message = "dshSessionId 不能超过 128 个字符")
        String dshSessionId,

        @NotNull(message = "nodeId 不能为空")
        Long nodeId,

        @Size(max = 200, message = "会话标题不能超过 200 个字符")
        String title) {
}
