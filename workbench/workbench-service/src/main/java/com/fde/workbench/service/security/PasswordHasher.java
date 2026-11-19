package com.fde.workbench.service.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 口令哈希组件（BCrypt）。
 * <p>
 * 只引入 {@code spring-security-crypto} 一个库，不启用 Spring Security 的过滤器链——鉴权由本工程的
 * {@code JwtAuthFilter} 完成（T1-2 再做更细的权限拦截）。
 * <p>
 * 空密码约定：注册时密码为空则不落库任何哈希（{@code password_hash = NULL}），因此本类不提供
 * "哈希空串"的入口，避免出现"空串哈希"与 NULL 两种语义并存。
 */
@Component
public class PasswordHasher {

    /** BCrypt 强度 10：登录一次约 50–100ms，兼顾安全与本地联调体验 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    /**
     * 计算 BCrypt 哈希。
     *
     * @param rawPassword 明文密码（调用方保证非空）
     * @return BCrypt 哈希串（60 字符，含盐）
     */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验明文密码与已存哈希是否匹配；哈希为 null（空密码注册）时恒为 false。
     *
     * @param rawPassword 明文密码，可空
     * @param passwordHash 库中的 BCrypt 哈希，可空
     * @return true 匹配
     */
    public boolean matches(String rawPassword, String passwordHash) {
        if (passwordHash == null || rawPassword == null) {
            return false;
        }
        return encoder.matches(rawPassword, passwordHash);
    }
}
