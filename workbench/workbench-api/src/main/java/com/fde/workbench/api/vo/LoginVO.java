package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.service.AuthService;

import java.time.OffsetDateTime;

/**
 * 登录结果（docs/api.md §2：{@code {token, expiresAt, user}}）。
 * <p>
 * {@code expiresAt} 以带 +08:00 偏移的 ISO-8601 输出，与 api.md §1.4 示例一致。
 *
 * @param token     JWT
 * @param expiresAt token 过期时刻
 * @param user      当前用户
 */
public record LoginVO(
        String token,

        // 以 +08:00 偏移输出（yyyy-MM-dd'T'HH:mm:ssXXX），见 TimeFormats
        OffsetDateTime expiresAt,

        UserVO user) {

    /**
     * 由服务层登录结果转换。
     *
     * @param result 登录结果
     * @return 视图对象
     */
    public static LoginVO of(AuthService.LoginResult result) {
        return new LoginVO(result.token(), TimeFormats.withOffset(result.expiresAt()), UserVO.of(result.user()));
    }
}
