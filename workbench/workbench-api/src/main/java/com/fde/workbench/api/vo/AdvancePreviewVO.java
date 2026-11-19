package com.fde.workbench.api.vo;

import java.util.List;

/**
 * 节点推进预览出网视图（api.md §8 advance-preview）。
 *
 * @param ready                    是否满足推进条件
 * @param remainingRequiredOutputs 还缺的必填输出项
 * @param remainingRequiredInputs  还缺文件的必填固定输入项
 * @param requiresAnyInput         自由输入节点是否还没有任何输入文件
 * @param missingDescriptions      缺项的中文说明
 */
public record AdvancePreviewVO(
        Boolean ready,
        List<String> remainingRequiredOutputs,
        List<Long> remainingRequiredInputs,
        Boolean requiresAnyInput,
        List<String> missingDescriptions) {

    /**
     * 由 service 视图转换。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static AdvancePreviewVO of(com.fde.workbench.service.vo.AdvancePreviewVO source) {
        return new AdvancePreviewVO(source.ready(), source.remainingRequiredOutputs(),
                source.remainingRequiredInputs(), source.requiresAnyInput(), source.missingDescriptions());
    }
}
