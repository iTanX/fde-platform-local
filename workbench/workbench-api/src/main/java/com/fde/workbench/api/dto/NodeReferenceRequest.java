package com.fde.workbench.api.dto;

/**
 * 引用登记请求（api.md §11 {@code POST /internal/nodes/{nid}/reference}）。
 *
 * @param kind     引用类型：input / previousOutput / ownOutput
 * @param fileId   文件 ID（必填）
 * @param itemKey  输出项 key（引用输出项时给出，可空）
 * @param operator 调用方标识（如 {@code agent}），可空
 */
public record NodeReferenceRequest(String kind, Long fileId, String itemKey, String operator) {
}
