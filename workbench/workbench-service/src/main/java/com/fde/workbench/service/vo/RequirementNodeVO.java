package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 节点信息页视图（docs/api.md §1.4 RequirementNodeVO、§8 GET 节点信息）。
 * <p>
 * 字段与 api.md 的示例逐字对齐；几个容易理解错的点：
 * <ul>
 *   <li>{@code id} 是<b>节点实例 ID</b>（{@code wb_requirement_node.id}），不是流程节点 ID——
 *       模型工具、输出目录、上传接口用的都是它；</li>
 *   <li>{@code inputs[].files[]} 只列"已上传的文件"，空数组表示还没传；同一固定输入项可以有多个文件；</li>
 *   <li>{@code outputs[]} 是<b>全部输出项</b>（含未提交的），{@code submitted=false} 表示模型还没提交；</li>
 *   <li>{@code previousNode} 为 {@code seq-1} 节点的输出文件；第一个节点为 {@code null}；</li>
 *   <li>{@code readonly} 由后端按 PRD AC-F4/F5/H5 三条规则判定（见 {@code NodeWorkflowService}），
 *       前端只看这个布尔值，不自己再推一遍。</li>
 * </ul>
 *
 * @param id               节点实例 ID
 * @param seq              执行顺序（从 1 开始）
 * @param name             节点名称（快照）
 * @param description      节点说明（快照），可空
 * @param status           waiting / current / done
 * @param inputType        free / fixed
 * @param skills           节点技能（含快照版本号）
 * @param inputs           固定输入项及其已上传文件
 * @param outputs          输出项及其提交状态
 * @param previousNode     上一节点输出，可空
 * @param readonly         是否只读（非当前节点 / 非需求创建人且非 owner / 需求已完成）
 * @param defaultSessionId 默认会话 DSH 标识，解析不到为 null
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
        String defaultSessionId) {

    /**
     * 节点技能。
     *
     * @param skillId   技能 ID
     * @param name      技能名称（快照）
     * @param versionNo 技能版本号（快照；技能当时没有版本时为 null）
     */
    public record SkillVO(Long skillId, String name, Integer versionNo) {
    }

    /**
     * 固定输入项 + 已上传文件。
     *
     * @param itemId      输入项 ID（与快照 {@code itemId} 一致）
     * @param name        名称
     * @param fileKind    text / document / any
     * @param required    是否必填（必填项没文件时节点不推进）
     * @param files       已上传文件（按上传先后）
     */
    public record InputVO(Long itemId, String name, String fileKind, Boolean required, List<FileVO> files) {
    }

    /**
     * 输出项 + 提交状态。
     *
     * @param itemKey     稳定键（模型工具按它提交）
     * @param name        名称
     * @param fileKind    text / document / any
     * @param required    是否必填
     * @param submitted   是否已提交（有当前版本）
     * @param versionNo   当前版本号；未提交为 null
     * @param committedAt 最近提交时间；未提交为 null
     * @param file        当前文件；未提交为 null
     */
    public record OutputVO(
            String itemKey,
            String name,
            String fileKind,
            Boolean required,
            Boolean submitted,
            Integer versionNo,

            @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
            OffsetDateTime committedAt,

            FileVO file) {
    }

    /**
     * 文件（输入 / 输出通用）。
     * <p>
     * 输入文件额外带 {@code inputRecordId}（删除接口要用它），输出文件为 null；
     * {@code inputItemId} 与 {@code inputRecordId} 在 api.md 的示例里没写全，
     * 但删输入必须有记录 ID、区分固定项必须知道归属项，因此按需补充，不改动已有字段。
     *
     * @param fileId         文件 ID（{@code GET /api/files/{fileId}/download} 用它下载）
     * @param originalName   原始文件名
     * @param sizeBytes      大小（字节）
     * @param uploadedAt     上传 / 提交时间，可空
     * @param inputRecordId  输入记录 ID（仅输入文件，删除时用）
     * @param inputItemId    归属固定输入项 ID（仅输入文件；自由输入为 null）
     */
    public record FileVO(
            Long fileId,
            String originalName,
            Long sizeBytes,

            @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
            OffsetDateTime uploadedAt,

            Long inputRecordId,
            Long inputItemId) {
    }

    /**
     * 上一节点输出。
     *
     * @param seq     上一节点顺序
     * @param name    上一节点名称
     * @param outputs 上一节点已提交的输出文件
     */
    public record PreviousNodeVO(Integer seq, String name, List<PreviousOutputVO> outputs) {
    }

    /**
     * 上一节点的某个输出项。
     *
     * @param itemKey 输出项稳定键
     * @param name    输出项名称
     * @param file    当前文件；未提交为 null
     */
    public record PreviousOutputVO(String itemKey, String name, FileVO file) {
    }

    /** 库中 +08:00 墙钟时间 → 带偏移时间；null 透传 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
    }
}
