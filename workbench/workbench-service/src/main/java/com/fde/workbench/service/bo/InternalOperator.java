package com.fde.workbench.service.bo;

/**
 * 一次内部接口调用（{@code /internal/**}）的操作人。
 * <p>
 * <b>为什么内部接口也需要"操作人"</b>：{@code wb_file.uploaded_by}、
 * {@code wb_requirement_node_output.committed_by}、{@code wb_requirement_node.completed_by}
 * 都是 {@code NOT NULL} 且外键指向 {@code wb_user}，插件进程没有用户身份，必须由 Java 侧
 * 解析出"这个节点属于哪个需求、需求是谁创建的"再记录下来，否则写入会失败或写错人。
 * <p>
 * {@code operator} 取插件传来的 {@code "agent"}（缺省也是 agent）：它只用于日志与审计语义
 * （区分"用户手动上传"与"模型产出"），<b>不决定数据库里的账号</b>——
 * 账号永远是需求创建人（见 {@code NodeWorkflowService#resolveInternalOperator}）。
 *
 * @param userId            记录到库里的用户 ID（需求创建人）
 * @param operator          调用方标识（agent / user）
 * @param nodeInstanceId    本次操作的节点实例 ID
 * @param requirementId     节点所属需求
 * @param projectId         需求所属项目
 * @param requirementTitle  需求标题（日志与上下文展示用）
 */
public record InternalOperator(Long userId, String operator, Long nodeInstanceId, Long requirementId,
                               Long projectId, String requirementTitle) {
}
