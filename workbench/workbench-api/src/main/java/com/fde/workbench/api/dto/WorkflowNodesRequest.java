package com.fde.workbench.api.dto;

import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.service.bo.WorkflowNodesCommand;

import java.util.List;

/**
 * 整体保存节点列表请求体（docs/api.md §6 {@code PUT /api/workflows/{id}/nodes}）。
 * <p>
 * 请求体结构完全按 api.md 的示例：
 * <pre>
 * { "revision": 2,
 *   "nodes": [ { "nodeId": 11, "name": "...", "description": "...", "inputType": "free",
 *                "skillIds": [1,4], "inputs": [], "outputs": [ ... ] } ] }
 * </pre>
 * <b>数组顺序即执行顺序</b>（校验规则 5），后端按数组下标重排 {@code seq}。
 * <p>
 * 这里刻意<b>不加</b> Jakarta Validation 注解：5 条校验规则都需要"哪个节点、哪一项"的定位信息，
 * 且顺序固定（先节点级、再输出项级、再技能归属），统一放在 {@code WorkflowService} 里做，
 * 才能保证任何一条失败都返回同一种 400/10001 文案格式。
 *
 * @param revision 流程版本号（必填，JSON 整数），值来自详情接口
 * @param nodes    节点列表
 */
public record WorkflowNodesRequest(RevisionStamp revision, List<NodeRequest> nodes) {

    /**
     * 单个节点。
     *
     * @param nodeId      已有节点 ID；{@code null} 表示新增
     * @param name        节点名称
     * @param description 节点说明，可空
     * @param inputType   {@code free} / {@code fixed}
     * @param skillIds    可用技能 ID 列表（顺序即加载顺序）
     * @param inputs      固定输入项（{@code free} 时忽略）
     * @param outputs     输出项（至少 1 项）
     */
    public record NodeRequest(Long nodeId,
                              String name,
                              String description,
                              String inputType,
                              List<Long> skillIds,
                              List<ItemRequest> inputs,
                              List<ItemRequest> outputs) {
    }

    /**
     * 输入项 / 输出项。
     *
     * @param itemKey     输出项稳定键（输出项必填，节点内唯一；输入项忽略）
     * @param name        名称
     * @param description 说明，可空
     * @param fileKind    {@code text} / {@code document} / {@code any}，缺省 {@code any}
     * @param required    是否必填，缺省 {@code true}
     */
    public record ItemRequest(String itemKey,
                              String name,
                              String description,
                              String fileKind,
                              Boolean required) {
    }

    /**
     * 转换为 service 层命令对象（DTO → BO：api 层只做结构搬运，不做业务校验）。
     *
     * @return 命令对象
     */
    public WorkflowNodesCommand toCommand() {
        List<WorkflowNodesCommand.Node> commandNodes = nodes == null ? null : nodes.stream()
                .map(node -> node == null ? null : new WorkflowNodesCommand.Node(
                        node.nodeId(),
                        node.name(),
                        node.description(),
                        node.inputType(),
                        node.skillIds(),
                        toCommandItems(node.inputs()),
                        toCommandItems(node.outputs())))
                .toList();
        return new WorkflowNodesCommand(revision, commandNodes);
    }

    private static List<WorkflowNodesCommand.Item> toCommandItems(List<ItemRequest> items) {
        return items == null ? null : items.stream()
                .map(item -> item == null ? null : new WorkflowNodesCommand.Item(
                        item.itemKey(), item.name(), item.description(), item.fileKind(), item.required()))
                .toList();
    }
}
