package com.fde.workbench.service.bo;

import com.fde.workbench.common.bo.RevisionStamp;

/**
 * 编辑需求命令（docs/api.md §7 {@code PUT /api/requirements/{id}}）。
 * <p>
 * {@code revision} 复用 common 的 {@link RevisionStamp} 作为 {@code @RequestParam} 绑定与 JSON 解析入口，
 * 但<b>语义不同</b>：需求表自带 {@code revision INT} 小整数列，这里收到的是 1、2、3 这样的版本号，
 * 不需要像流程那样换算成 {@code updated_at} 毫秒时间戳。用同一个类型只为复用"整数解析 + 空值表示缺失"
 * 这两条约定，Service 里通过 {@link RevisionStamp#epochMilli()} 取出原始整数值。
 *
 * @param title       新标题（1–200 字符）
 * @param description 新说明，可空
 * @param revision    前端持有的 revision（必填）
 */
public record RequirementUpdateCommand(String title, String description, RevisionStamp revision) {
}
