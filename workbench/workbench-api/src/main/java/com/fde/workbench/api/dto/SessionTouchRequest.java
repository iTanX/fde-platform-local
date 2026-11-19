package com.fde.workbench.api.dto;

/**
 * 更新会话活跃时间的可选请求体。
 *
 * @param nodeId 当前节点实例 ID，可空；传入时同时保存该节点的默认会话
 */
public record SessionTouchRequest(Long nodeId) {
}
