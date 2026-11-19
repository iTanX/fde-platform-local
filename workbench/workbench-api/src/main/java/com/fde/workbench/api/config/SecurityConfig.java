package com.fde.workbench.api.config;

import com.fde.workbench.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全相关 Bean 装配：从配置读取 JWT 密钥与有效期，构造 JwtUtil。
 * <p>
 * 密钥来源：application.yml 的 {@code security.jwt.secret}，默认取环境变量 {@code FDE_JWT_SECRET}。
 */
@Configuration
public class SecurityConfig {

    /**
     * JWT 工具 Bean。
     *
     * @param secret      HS256 密钥（至少 32 字节）
     * @param expireHours token 有效期（小时）
     */
    @Bean
    public JwtUtil jwtUtil(@Value("${security.jwt.secret}") String secret,
                           @Value("${security.jwt.expire-hours:12}") long expireHours) {
        return new JwtUtil(secret, expireHours);
    }
}
