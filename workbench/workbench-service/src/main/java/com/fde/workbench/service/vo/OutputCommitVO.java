package com.fde.workbench.service.vo;

import java.util.List;

/**
 * 输出提交结果（docs/api.md §11 commit 的响应 {@code data}）。
 *
 * @param committed                是否已登记成功（走到这里就是 true）
 * @param versionNo                本次登记的版本号（重新提交递增）
 * @param advanced                 是否因此推进了节点
 * @param nextNodeName             推进后的下一节点名称；未推进或已是最后节点为 null
 * @param requirementCompleted     是否因本次提交使整个需求完成（最后一个节点）
 * @param remainingRequiredOutputs 仍未提交的必填输出项 key（空数组表示输出侧齐备）
 * @param remainingRequiredInputs  仍缺文件的必填固定输入项 ID（导致不推进时给出原因）
 */
public record OutputCommitVO(
        Boolean committed,
        Integer versionNo,
        Boolean advanced,
        String nextNodeName,
        Boolean requirementCompleted,
        List<String> remainingRequiredOutputs,
        List<Long> remainingRequiredInputs) {
}
