package com.fde.workbench.api.dto;

/**
 * 输出项提交请求（api.md §11 {@code POST /internal/nodes/{nid}/outputs/{itemKey}/commit}）。
 * <p>
 * 不做 Jakarta Validation：内部接口的调用方是插件，参数错误要以"可读的业务错误"
 * （400/10001 + 中文说明）返回给模型，而不是字段校验的错误列表。
 * 具体规则（路径越界、sha256 格式与一致性）在 Service 里逐条判定。
 *
 * @param relativePath 相对 workspace 根的路径，如 {@code .workbench/101/requirement-items.md}
 * @param sha256       文件内容的 SHA-256（64 位十六进制）
 * @param operator     调用方标识（如 {@code agent}），可空
 * @param requirementId 可选：需求 ID（仅用于日志校验，节点本身已能定位需求）
 * @param nodeId       可选：节点实例 ID（与路径参数不一致时以路径参数为准）
 */
public record OutputCommitRequest(String relativePath, String sha256, String operator,
                                  Long requirementId, Long nodeId) {
}
