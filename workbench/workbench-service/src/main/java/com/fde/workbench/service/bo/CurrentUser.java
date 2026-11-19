package com.fde.workbench.service.bo;

/**
 * 当前登录用户快照（不含密码哈希），由认证过滤器写入请求上下文，Service 层只依赖本类与 HTTP 无关。
 *
 * @param userId      用户 ID
 * @param username    用户名
 * @param displayName 展示名称
 */
public record CurrentUser(Long userId, String username, String displayName) {
}
