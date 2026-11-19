package com.fde.workbench.api.vo;

import java.util.List;

/**
 * 输出提交结果出网视图（api.md §11 commit 响应 {@code data}）。
 * <p>
 * 插件 Host 会把这份结果原样回给模型，因此字段名与 api.md 保持一致，
 * 并额外带 {@code requirementCompleted}（最后一个节点完成）供前端做"需求已完成"提示。
 *
 * @param committed                是否已登记
 * @param versionNo                版本号
 * @param advanced                 是否推进
 * @param nextNodeName             下一节点名称，可空
 * @param requirementCompleted     需求是否因此完成
 * @param remainingRequiredOutputs 还缺的必填输出项
 * @param remainingRequiredInputs  还缺文件的必填固定输入项
 */
public record OutputCommitVO(
        Boolean committed,
        Integer versionNo,
        Boolean advanced,
        String nextNodeName,
        Boolean requirementCompleted,
        List<String> remainingRequiredOutputs,
        List<Long> remainingRequiredInputs) {

    /**
     * 由 service 视图转换。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static OutputCommitVO of(com.fde.workbench.service.vo.OutputCommitVO source) {
        return new OutputCommitVO(source.committed(), source.versionNo(), source.advanced(),
                source.nextNodeName(), source.requirementCompleted(), source.remainingRequiredOutputs(),
                source.remainingRequiredInputs());
    }
}
