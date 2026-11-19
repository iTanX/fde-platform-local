package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 节点信息页出网视图（api.md §1.4 RequirementNodeVO、§8 GET 节点信息）。
 * <p>
 * 与 service 层 {@code RequirementNodeVO} 字段逐一对应，单独再声明一次的原因与
 * {@link RequirementDetailVO} 相同：出网时间必须用 api 层的 {@link TimeFormats}（带 {@code +08:00}），
 * 而 service 模块不能反向依赖 api 模块。
 *
 * @param id               节点实例 ID
 * @param seq              执行顺序
 * @param name             节点名称
 * @param description      节点说明，可空
 * @param status           waiting / current / done
 * @param inputType        free / fixed
 * @param skills           节点技能
 * @param inputs           固定输入项与已上传文件
 * @param outputs          输出项与提交状态
 * @param previousNode     上一节点输出，可空
 * @param readonly         是否只读
 * @param defaultSessionId 默认会话 DSH 标识，可空
 * @param workspacePath    可操作节点所属需求的工作区绝对路径，只读时为 null
 */
public record RequirementNodeVO(
        Long id,
        Integer seq,
        String name,
        String description,
        String status,
        String inputType,
        List<SkillVO> skills,
        List<InputVO> inputs,
        List<OutputVO> outputs,
        PreviousNodeVO previousNode,
        Boolean readonly,
        String defaultSessionId,
        String workspacePath) {

    /**
     * 节点技能。
     *
     * @param skillId   技能 ID
     * @param name      技能名称
     * @param versionNo 技能版本号
     */
    public record SkillVO(Long skillId, String name, Integer versionNo) {
    }

    /**
     * 固定输入项。
     *
     * @param itemId   输入项 ID（free 节点为 null）
     * @param name     名称（free 节点为 null，前端按 inputType 渲染"节点输入"整体区块）
     * @param fileKind 文件类型
     * @param required 是否必填
     * @param files    已上传文件
     */
    public record InputVO(Long itemId, String name, String fileKind, Boolean required, List<FileVO> files) {
    }

    /**
     * 输出项。
     *
     * @param itemKey     稳定键
     * @param name        名称
     * @param fileKind    文件类型
     * @param required    是否必填
     * @param submitted   是否已提交
     * @param versionNo   当前版本号
     * @param committedAt 最近提交时间
     * @param file        当前文件
     */
    public record OutputVO(
            String itemKey,
            String name,
            String fileKind,
            Boolean required,
            Boolean submitted,
            Integer versionNo,

            @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
            LocalDateTime committedAt,

            FileVO file) {
    }

    /**
     * 文件。
     *
     * @param fileId        文件 ID
     * @param originalName  原始文件名
     * @param sizeBytes     大小
     * @param uploadedAt    上传 / 提交时间
     * @param inputRecordId 输入记录 ID（仅输入文件）
     * @param inputItemId   归属输入项 ID（仅输入文件）
     */
    public record FileVO(
            Long fileId,
            String originalName,
            Long sizeBytes,

            @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
            LocalDateTime uploadedAt,

            Long inputRecordId,
            Long inputItemId) {
    }

    /**
     * 上一节点输出。
     *
     * @param seq     上一节点顺序
     * @param name    上一节点名称
     * @param outputs 输出文件
     */
    public record PreviousNodeVO(Integer seq, String name, List<PreviousOutputVO> outputs) {
    }

    /**
     * 上一节点输出项。
     *
     * @param itemKey 稳定键
     * @param name    名称
     * @param file    文件；未提交为 null
     */
    public record PreviousOutputVO(String itemKey, String name, FileVO file) {
    }

    /**
     * 由 service 视图转换。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static RequirementNodeVO of(com.fde.workbench.service.vo.RequirementNodeVO source) {
        List<SkillVO> skills = source.skills().stream()
                .map(item -> new SkillVO(item.skillId(), item.name(), item.versionNo()))
                .toList();
        List<InputVO> inputs = source.inputs().stream()
                .map(item -> new InputVO(item.itemId(), item.name(), item.fileKind(), item.required(),
                        item.files().stream().map(RequirementNodeVO::toFile).toList()))
                .toList();
        List<OutputVO> outputs = source.outputs().stream()
                .map(item -> new OutputVO(item.itemKey(), item.name(), item.fileKind(), item.required(),
                        item.submitted(), item.versionNo(), toLocal(item.committedAt()), toFile(item.file())))
                .toList();
        PreviousNodeVO previous = source.previousNode() == null ? null
                : new PreviousNodeVO(source.previousNode().seq(), source.previousNode().name(),
                source.previousNode().outputs().stream()
                        .map(item -> new PreviousOutputVO(item.itemKey(), item.name(), toFile(item.file())))
                        .toList());
        return new RequirementNodeVO(source.id(), source.seq(), source.name(), source.description(),
                source.status(), source.inputType(), skills, inputs, outputs, previous, source.readonly(),
                source.defaultSessionId(), source.workspacePath());
    }

    private static FileVO toFile(com.fde.workbench.service.vo.RequirementNodeVO.FileVO file) {
        return file == null ? null : new FileVO(file.fileId(), file.originalName(), file.sizeBytes(),
                toLocal(file.uploadedAt()), file.inputRecordId(), file.inputItemId());
    }

    private static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
