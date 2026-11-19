package com.fde.workbench.service.bo;

/**
 * 登记新会话的命令（docs/api.md §9 POST {@code /api/requirements/{rid}/sessions}，
 * §11 {@code /internal/sessions} 同一结构）。
 *
 * @param dshSessionId DSH 会话标识（必填；全局唯一，重复 → 409/10401）
 * @param nodeId       当前节点实例 ID；传入时将新会话设为节点默认并记录弱关联
 * @param title        会话标题，可空
 */
public record SessionRegisterCommand(String dshSessionId, Long nodeId, String title) {
}
