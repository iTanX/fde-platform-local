package com.fde.workbench.service.bo;

import com.fde.workbench.common.bo.RevisionStamp;

import java.util.List;

/**
 * "整体保存节点列表"的领域输入（由 api 层 DTO 转换而来，docs/api.md §6
 * {@code PUT /api/workflows/{id}/nodes}）。
 * <p>
 * 只做数据搬运，<b>不做任何校验</b>：所有校验规则（至少一个节点、itemKey 格式与唯一性、
 * fixed 必须有输入项、技能归属）都集中在 {@code WorkflowService} 里，
 * 这样 Controller / 前端 / 测试拿到的是同一套错误文案与错误码。
 *
 * @param revision 前端持有的流程版本号（= {@code wb_workflow.updated_at} 的毫秒时间戳），可空
 * @param nodes    节点列表，<b>数组顺序即执行顺序</b>（seq 从 1 开始按顺序重排）
 */
public record WorkflowNodesCommand(RevisionStamp revision, List<Node> nodes) {

    /**
     * 单个节点。
     *
     * @param nodeId      已有节点 ID；{@code null} 表示新增节点
     * @param name        节点名称
     * @param description 节点说明，可空
     * @param inputType   {@code free} / {@code fixed}
     * @param skillIds    可用技能 ID（顺序即加载顺序）
     * @param inputs      固定输入项（{@code inputType=free} 时忽略）
     * @param outputs     输出项（至少 1 项）
     */
    public record Node(Long nodeId,
                       String name,
                       String description,
                       String inputType,
                       List<Long> skillIds,
                       List<Item> inputs,
                       List<Item> outputs) {
    }

    /**
     * 输入项 / 输出项。
     * <p>
     * 输入项没有稳定键（库里没有 {@code item_key}），因此 {@code itemKey} 只在输出项上有意义；
     * 整体保存时输入项与输出项都按列表重建，{@code itemId} 仅用于前端回显，不参与匹配。
     *
     * @param itemKey     输出项稳定键（输出项必填；输入项忽略）
     * @param name        名称
     * @param description 说明 / 输出要求，可空
     * @param fileKind    {@code text} / {@code document} / {@code any}；缺省 {@code any}
     * @param required    是否必填；缺省 {@code true}
     */
    public record Item(String itemKey,
                       String name,
                       String description,
                       String fileKind,
                       Boolean required) {
    }
}
