package com.fde.workbench.api.dto;

import com.fde.workbench.common.bo.RevisionStamp;

/**
 * 编辑需求请求体（docs/api.md §7 {@code PUT /api/requirements/{id}}）。
 * <pre>
 * { "title": "新的标题", "description": "…", "revision": 3 }
 * </pre>
 * 刻意<b>不</b>接受 {@code workflowId}：PRD AC-F2.1 要求需求创建后流程不可更换，
 * 用请求体形状把这个约束表达出来，比在 Service 里再校验一次更可靠。
 * <p>
 * {@code revision} 用 {@link RevisionStamp} 只是为了复用"JSON 整数解析 + 缺失即 null"这套绑定，
 * 这里收到的就是需求表 {@code revision INT} 的原值（1、2、3……），不做时间戳换算。
 *
 * @param title       新标题，1–200 字符
 * @param description 新说明，可空
 * @param revision    前端持有的 revision（必填；缺失或不匹配分别返回 400/10001 与 409/10402）
 */
public record RequirementUpdateRequest(
        String title,
        String description,
        RevisionStamp revision) {
}
