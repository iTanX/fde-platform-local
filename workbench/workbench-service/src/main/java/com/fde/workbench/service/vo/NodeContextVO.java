package com.fde.workbench.service.vo;

import java.util.List;

/**
 * 节点上下文（docs/api.md §11 {@code GET /internal/nodes/{nodeInstanceId}/context}，
 * 供插件 Host 工具 {@code node_context_get} 使用，见 plugin-integration.md §5.2）。
 * <p>
 * <b>给谁看</b>：模型。因此这里的路径一律是<b>相对 workspace 根</b>的写法（{@code inputs/1/...}、
 * {@code .workbench/<nid>/...}），与模型 cwd 一致；模型可以直接用 {@code read} 工具读相对路径，
 * 也能直接往 {@code outputDir} 里写。绝对路径只出现在 {@code workspacePath} 一处，供插件解析。
 * <p>
 * <b>为什么不带文件正文</b>：输入文件可能很大，正文按需由 {@code node_reference_read} 读取并截断
 * （plugin-integration.md §5.2），上下文只给"有什么、在哪、多大、校验值"。
 *
 * @param nodeInstanceId      节点实例 ID
 * @param requirementId       需求 ID
 * @param requirementTitle    需求标题
 * @param requirementStatus   in_progress / completed
 * @param workspacePath       需求 workspace 绝对路径（= DSH session cwd）
 * @param nodeSeq             节点顺序
 * @param nodeCount           需求节点总数
 * @param nodeName            节点名称
 * @param nodeDescription     节点说明，可空
 * @param nodeStatus          waiting / current / done
 * @param inputType           free / fixed
 * @param readonly            该节点当前是否只读（非当前节点 / 需求已完成）
 * @param skills              生效技能（名称 + 快照版本号；模型按名称加载技能）
 * @param inputItems          固定输入项及文件（free 节点为空数组，文件在 {@code freeInputFiles}）
 * @param freeInputFiles      free 节点的全部上传文件
 * @param outputs             输出项及完成状态
 * @param previousNode        上一节点输出，可空
 * @param outputDir           模型输出目录（相对 workspace，形如 {@code .workbench/101/}）
 * @param nextNodeName        下一节点名称，可空（已是最后节点）
 */
public record NodeContextVO(
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
        List<SkillRef> skills,
        List<InputFileRef> inputItems,
        List<FileRef> freeInputFiles,
        List<OutputItemRef> outputs,
        PreviousNode previousNode,
        String outputDir,
        String nextNodeName) {

    /**
     * 生效技能。
     *
     * @param name      技能名称（模型据此加载）
     * @param versionNo 快照版本号，可空
     */
    public record SkillRef(String name, Integer versionNo) {
    }

    /**
     * 固定输入项。
     *
     * @param itemId   输入项 ID
     * @param name     名称
     * @param fileKind text / document / any
     * @param required 是否必填
     * @param files    已上传文件
     */
    public record InputFileRef(Long itemId, String name, String fileKind, Boolean required,
                               List<FileRef> files) {
    }

    /**
     * 文件引用（相对 workspace 路径）。
     *
     * @param fileId   文件 ID
     * @param name     原始文件名
     * @param relativePath 相对 workspace 根的路径（模型可直接 read）
     * @param sizeBytes 大小
     * @param sha256   内容摘要
     */
    public record FileRef(Long fileId, String name, String relativePath, Long sizeBytes, String sha256) {
    }

    /**
     * 输出项。
     *
     * @param itemKey 稳定键（提交时用它）
     * @param name    名称
     * @param fileKind text / document / any（写文件的扩展名要匹配）
     * @param required 是否必填
     * @param submitted 是否已提交
     * @param versionNo 当前版本号，未提交为 null
     * @param fileName  当前文件名，未提交为 null
     * @param relativePath 当前文件相对路径，未提交为 null
     * @param suggestPath 建议的写入路径（{@code .workbench/<nid>/<itemKey>.md}），永远是"该写的那个位置"
     */
    public record OutputItemRef(String itemKey, String name, String fileKind, Boolean required,
                                Boolean submitted, Integer versionNo, String fileName,
                                String relativePath, String suggestPath) {
    }

    /**
     * 上一节点输出。
     *
     * @param seq     上一节点顺序
     * @param name    上一节点名称
     * @param outputs 已提交的输出文件
     */
    public record PreviousNode(Integer seq, String name, List<PreviousOutput> outputs) {
    }

    /**
     * 上一节点输出项。
     *
     * @param itemKey      稳定键
     * @param name         名称
     * @param relativePath 相对 workspace 路径（可 read）
     * @param fileId       文件 ID
     */
    public record PreviousOutput(String itemKey, String name, String relativePath, Long fileId) {
    }
}
