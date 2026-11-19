package com.fde.workbench.api.vo;

import java.util.List;

/**
 * 节点上下文出网视图（api.md §11 {@code GET /internal/nodes/{nid}/context}）。
 * <p>
 * 结构完全沿用 service 层 {@code NodeContextVO}（内部接口没有时间字段，不存在时区序列化差异），
 * 这里只做类型别名，保证 Controller 的返回类型落在 api 层、不把 service 内部类型直接漏到 HTTP 契约里。
 */
public final class NodeContextVO {

    private NodeContextVO() {
    }

    /**
     * 上下文本体（字段说明见 {@code com.fde.workbench.service.vo.NodeContextVO}）。
     *
     * @param nodeInstanceId      节点实例 ID
     * @param requirementId       需求 ID
     * @param requirementTitle    需求标题
     * @param requirementStatus   需求状态
     * @param workspacePath       workspace 绝对路径
     * @param nodeSeq             节点顺序
     * @param nodeCount           节点总数
     * @param nodeName            节点名称
     * @param nodeDescription     节点说明
     * @param nodeStatus          节点状态
     * @param inputType           输入类型
     * @param readonly            是否只读
     * @param skills              生效技能
     * @param inputItems          固定输入项与文件
     * @param freeInputFiles      自由输入文件
     * @param outputs             输出项与完成状态
     * @param previousNode        上一节点输出
     * @param outputDir           模型输出目录（相对 workspace）
     * @param nextNodeName        下一节点名称
     */
    public record Context(
            Long nodeInstanceId,
            Long requirementId,
            String requirementTitle,
            String requirementStatus,
            String workspacePath,
            Integer nodeSeq,
            Integer nodeCount,
            String nodeName,
            String nodeDescription,
            String nodeStatus,
            String inputType,
            Boolean readonly,
            List<com.fde.workbench.service.vo.NodeContextVO.SkillRef> skills,
            List<com.fde.workbench.service.vo.NodeContextVO.InputFileRef> inputItems,
            List<com.fde.workbench.service.vo.NodeContextVO.FileRef> freeInputFiles,
            List<com.fde.workbench.service.vo.NodeContextVO.OutputItemRef> outputs,
            com.fde.workbench.service.vo.NodeContextVO.PreviousNode previousNode,
            String outputDir,
            String nextNodeName) {

        /**
         * 由 service 视图转换（字段同构，直接搬运）。
         *
         * @param source service 层视图
         * @return 出网视图
         */
        public static Context of(com.fde.workbench.service.vo.NodeContextVO source) {
            return new Context(source.nodeInstanceId(), source.requirementId(), source.requirementTitle(),
                    source.requirementStatus(), source.workspacePath(), source.nodeSeq(), source.nodeCount(),
                    source.nodeName(), source.nodeDescription(), source.nodeStatus(), source.inputType(),
                    source.readonly(), source.skills(), source.inputItems(), source.freeInputFiles(),
                    source.outputs(), source.previousNode(), source.outputDir(), source.nextNodeName());
        }
    }

    /**
     * 内部接口登记会话的结果（api.md §11 {@code POST /internal/sessions}）。
     *
     * @param session 新会话（字段同 SessionVO）
     */
    public record SessionRegistration(SessionVO session) {
    }

    /**
     * 引用登记结果（api.md §11 {@code POST /internal/.../reference}）。
     *
     * @param recorded 是否已记录
     * @param fileId   文件 ID
     * @param fileName 文件名
     * @param kind     引用类型
     */
    public record Reference(Boolean recorded, Long fileId, String fileName, String kind) {
    }
}
