package com.fde.workbench.service.bo;

/**
 * 引用登记命令（docs/api.md §11 {@code POST /internal/nodes/{nodeInstanceId}/reference}）。
 * <p>
 * 用途：插件在模型读取了某个节点输入 / 上一节点输出后回调本接口，Java 侧<b>校验该文件确属本节点上下文</b>
 * 并写日志（"模型读了什么"）。不单独建表：文件归属本身已由
 * {@code wb_requirement_node_input} 与 {@code wb_requirement_node_output} 表达，再建一张流水表
 * 会把每个 token 的读取都变成一行数据库记录，收益远小于成本；需要审计时查应用日志里的
 * {@code node-reference} 关键字。
 *
 * @param kind     input / previousOutput / ownOutput（不区分大小写）
 * @param fileId   文件 ID（{@code node_reference_read} 拿到的 fileId）
 * @param itemKey  输出项 key（引用的是某个输出项时给出，可选）
 * @param operator 调用方标识，如 {@code agent}
 */
public record NodeReferenceCommand(String kind, Long fileId, String itemKey, String operator) {
}
