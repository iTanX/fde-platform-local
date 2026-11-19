package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 节点输入上传结果（docs/api.md §8 POST 上传输入的成功响应：{@code {fileId, originalName, sizeBytes}}）。
 * <p>
 * 额外返回 {@code inputRecordId} 与 {@code inputItemId}：前者用于删除该输入
 * （{@code DELETE .../inputs/{inputRecordId}}），后者用于前端把文件挂到对应输入项下。
 * <p>
 * <b>为什么还要返回推进结果（端到端阻塞问题）</b>：上传输入现在也是推进的触发点——
 * 用户"先让模型产出、再补传必填资料"时，补传完这一次上传就会把当前节点推下去。
 * 页面需要立刻知道"节点已变"，否则会继续停在旧节点页（只刷输入区）。因此上传响应与
 * {@code commit} 同口径地给出 {@code advanced / nextNodeName / requirementCompleted /
 * remainingRequiredOutputs / remainingRequiredInputs}，前端据此刷新或提示。
 * <p>
 * 未推进时（常见情况：还差别的必填项）{@code advanced=false}，{@code remaining*} 指出还差什么。
 *
 * @param fileId                    文件 ID
 * @param originalName              原始文件名
 * @param sizeBytes                 大小
 * @param inputRecordId             输入记录 ID
 * @param inputItemId               归属固定输入项 ID（自由输入为 null）
 * @param uploadedAt                上传时间
 * @param advanced                  本次上传是否触发了节点推进
 * @param nextNodeName              推进后的下一节点名（未推进或需求已全部完成时为 null）
 * @param requirementCompleted      是否因本次上传让需求整体完成（最后节点的缺项恰好由这次上传补齐）
 * @param remainingRequiredOutputs  仍缺的必填输出项 itemKey
 * @param remainingRequiredInputs   仍缺文件的必填固定输入项 ID
 */
public record NodeInputUploadVO(
        Long fileId,
        String originalName,
        Long sizeBytes,
        Long inputRecordId,
        Long inputItemId,

        @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
        OffsetDateTime uploadedAt,

        Boolean advanced,
        String nextNodeName,
        Boolean requirementCompleted,
        List<String> remainingRequiredOutputs,
        List<Long> remainingRequiredInputs) {

    /** 库中 +08:00 墙钟时间 → 带偏移时间；null 透传 */
    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
    }
}
