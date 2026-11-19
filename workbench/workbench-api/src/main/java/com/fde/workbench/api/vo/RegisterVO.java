package com.fde.workbench.api.vo;

/**
 * 注册结果（docs/api.md §2：{@code { "code": 0, "data": { "userId": 1 } }}）。
 *
 * @param userId 新用户 ID
 */
public record RegisterVO(Long userId) {
}
