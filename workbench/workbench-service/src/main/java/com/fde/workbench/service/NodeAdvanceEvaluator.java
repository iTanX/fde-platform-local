package com.fde.workbench.service;

import com.fde.workbench.dao.entity.NodeInputFile;
import com.fde.workbench.dao.entity.NodeOutputRecord;
import com.fde.workbench.dao.entity.WorkflowNode;
import com.fde.workbench.dao.mapper.NodeInputMapper;
import com.fde.workbench.dao.mapper.NodeOutputMapper;
import com.fde.workbench.service.vo.AdvancePreviewVO;
import com.fde.workbench.service.vo.NodeSnapshots;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 节点推进条件的<b>唯一判定实现</b>（docs/database.md §5.3、plugin-integration.md §7）。
 * <p>
 * 判定口径（两条必须<b>同时</b>满足才推进）：
 * <ol>
 *   <li><b>输出侧</b>：所有 {@code required} 输出项在 {@code wb_requirement_node_output} 中都有记录；</li>
 *   <li><b>输入侧</b>：{@code input_type=fixed} 时所有 {@code required} 固定输入项都至少有一个输入文件；
 *       {@code input_type=free} 时至少有一个 {@code input_item_id IS NULL} 的输入文件。</li>
 * </ol>
 * 半提交绝不推进——这也是为什么"输出齐了但必填输入没传"时 {@code commit} 返回
 * {@code advanced=false} 并给出缺的输入项：模型能据此提醒用户先传资料，而不是让页面假装完成。
 * <p>
 * 为什么单独成一个类而不是写在 {@code NodeAdvanceService} 里：{@code commit}（写路径）与
 * {@code advance-preview}（读路径）必须用<b>同一段代码</b>回答"还差什么"，
 * 两处各写一遍迟早会在某次规则调整后对不上。
 */
@Component
public class NodeAdvanceEvaluator {

    private final NodeInputMapper nodeInputMapper;

    private final NodeOutputMapper nodeOutputMapper;

    public NodeAdvanceEvaluator(NodeInputMapper nodeInputMapper, NodeOutputMapper nodeOutputMapper) {
        this.nodeInputMapper = nodeInputMapper;
        this.nodeOutputMapper = nodeOutputMapper;
    }

    /**
     * 评估某节点是否满足推进条件。
     *
     * @param nodeId    节点实例 ID
     * @param snapshot 已解析的快照
     * @return 评估结果
     */
    public Evaluation evaluate(Long nodeId, String inputType, NodeSnapshots.NodeSnapshot snapshot) {
        Set<String> committedKeys = nodeOutputMapper.selectByNode(nodeId).stream()
                .map(NodeOutputRecord::getItemKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> missingOutputs = snapshot.outputs().stream()
                .filter(item -> Boolean.TRUE.equals(item.required()))
                .map(NodeSnapshots.OutputItem::itemKey)
                .filter(key -> !committedKeys.contains(key))
                .toList();

        List<Long> missingInputs = new ArrayList<>();
        boolean requiresAnyInput = false;
        if (WorkflowNode.INPUT_TYPE_FREE.equals(inputType)) {
            // free：整节点只有一个上传入口，至少要有一个 input_item_id 为 NULL 的文件
            requiresAnyInput = nodeInputMapper.countByNode(nodeId) == 0;
        } else {
            for (NodeSnapshots.InputItem item : snapshot.inputs()) {
                if (Boolean.TRUE.equals(item.required())
                        && nodeInputMapper.countByNodeAndItem(nodeId, item.itemId()) == 0) {
                    missingInputs.add(item.itemId());
                }
            }
        }
        return new Evaluation(missingOutputs, missingInputs, requiresAnyInput, snapshot);
    }

    /**
     * 评估并转成"缺项说明"（advance-preview 用）。
     *
     * @param nodeId    节点实例 ID
     * @param inputType free / fixed
     * @param snapshot  快照
     * @return 预览视图
     */
    public AdvancePreviewVO preview(Long nodeId, String inputType, NodeSnapshots.NodeSnapshot snapshot) {
        Evaluation evaluation = evaluate(nodeId, inputType, snapshot);
        List<String> descriptions = new ArrayList<>();
        for (String key : evaluation.missingOutputs()) {
            snapshot.outputs().stream()
                    .filter(item -> item.itemKey().equals(key))
                    .findFirst()
                    .ifPresent(item -> descriptions.add("缺少必填输出项：" + item.name() + "（" + key + "）"));
        }
        for (Long itemId : evaluation.missingInputs()) {
            snapshot.inputs().stream()
                    .filter(item -> item.itemId().equals(itemId))
                    .findFirst()
                    .ifPresent(item -> descriptions.add("缺少必填输入文件：" + item.name()));
        }
        if (evaluation.requiresAnyInput()) {
            descriptions.add("缺少节点输入文件（自由输入节点至少上传一个）");
        }
        return new AdvancePreviewVO(evaluation.ready(), evaluation.missingOutputs(),
                evaluation.missingInputs(), evaluation.requiresAnyInput(), descriptions);
    }

    /**
     * 某节点当前已上传的输入文件（输入区展示与上下文用）。
     *
     * @param nodeId 节点实例 ID
     * @return 输入记录（含文件展示字段）
     */
    public List<NodeInputFile> inputFiles(Long nodeId) {
        return nodeInputMapper.selectByNode(nodeId);
    }

    /**
     * 评估结果。
     *
     * @param missingOutputs   还缺的必填输出项 key
     * @param missingInputs    还缺文件的必填固定输入项 ID（free 节点为空）
     * @param requiresAnyInput free 节点还没有任何输入文件
     * @param snapshot         本次判定所用的快照（调用方复用，省一次解析）
     */
    public record Evaluation(List<String> missingOutputs, List<Long> missingInputs,
                             boolean requiresAnyInput, NodeSnapshots.NodeSnapshot snapshot) {

        /**
         * 是否满足推进条件：输出齐 且 输入齐。
         *
         * @return true 表示可以推进
         */
        public boolean ready() {
            return missingOutputs.isEmpty() && missingInputs.isEmpty() && !requiresAnyInput;
        }
    }
}
