package com.fde.workbench.service.vo;

import java.util.List;

/**
 * 节点推进预览（docs/api.md §8 {@code GET .../advance-preview}、§11 commit 判定口径）。
 * <p>
 * 与 {@code commit} 的 {@code remainingRequiredOutputs} 共用同一套判定（{@code NodeAdvanceEvaluator}），
 * 因此"预览说还差什么"与"提交时为什么没推进"永远一致。
 *
 * @param ready                      是否满足推进条件（与 commit 返回的 {@code advanced} 同口径）
 * @param remainingRequiredOutputs   还缺的必填输出项 key
 * @param remainingRequiredInputs    还缺的必填固定输入项 ID（{@code input_type=free} 节点为空）
 * @param requiresAnyInput           {@code input_type=free} 且还没有任何输入文件时为 true
 * @param missingDescriptions        人读的缺项说明（中文，前端可直接展示）
 */
public record AdvancePreviewVO(
        Boolean ready,
        List<String> remainingRequiredOutputs,
        List<Long> remainingRequiredInputs,
        Boolean requiresAnyInput,
        List<String> missingDescriptions) {
}
