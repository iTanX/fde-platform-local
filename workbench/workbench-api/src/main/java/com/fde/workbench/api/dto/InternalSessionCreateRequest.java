package com.fde.workbench.api.dto;

/**
 * 内部接口登记会话请求（api.md §11 {@code POST /internal/sessions}，与 §9 的 POST 同结构但免 JWT）。
 * <p>
 * 比用户侧多一个 {@code requirementId}：插件（Host 工具）手里可能只有 workspace 或节点，
 * 需求 ID 由它显式给出；{@code nodeId} 可空（为空时仅登记到需求）。
 *
 * @param requirementId 需求 ID（必填）
 * @param dshSessionId  DSH 会话标识（必填）
 * @param nodeId        绑定节点实例 ID（可空）
 * @param title         会话标题（可空）
 */
public record InternalSessionCreateRequest(Long requirementId, String dshSessionId, Long nodeId, String title) {
}
