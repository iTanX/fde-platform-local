package com.fde.workbench.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 工具（HS256）：签发与解析 token。
 * <p>
 * 密钥与有效期由调用方从配置注入（application.yml 的 {@code security.jwt.secret} / {@code security.jwt.expire-hours}），
 * 本类不读取任何配置、不保存明文密码。
 */
public class JwtUtil {

    /** HS256 要求的最小密钥字节数（RFC 7518），避免弱密钥启动即失败 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 业务自定义声明：用户 ID */
    public static final String CLAIM_USER_ID = "uid";

    /** 业务自定义声明：用户名 */
    public static final String CLAIM_USERNAME = "username";

    /** HS256 签名密钥 */
    private final SecretKey key;

    /** token 有效期 */
    private final Duration expireDuration;

    /**
     * 构造 JWT 工具。
     *
     * @param secret        密钥字符串（至少 32 字节）
     * @param expireHours   有效期（小时）
     */
    public JwtUtil(String secret, long expireHours) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("security.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节");
        }
        if (expireHours <= 0) {
            throw new IllegalArgumentException("security.jwt.expire-hours 必须大于 0");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireDuration = Duration.ofHours(expireHours);
    }

    /**
     * 签发 token。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @return JWT 字符串
     */
    public String generateToken(long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireDuration)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 token（签名、过期时间）。
     *
     * @param token JWT 字符串
     * @return 声明集合
     * @throws JwtException 签名不合法或已过期时抛出，由调用方转为 401 / 10101
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 token 中取出用户 ID。
     *
     * @param token JWT 字符串
     * @return 用户 ID
     * @throws JwtException 签名不合法或已过期时抛出
     */
    public long parseUserId(String token) {
        return parse(token).get(CLAIM_USER_ID, Number.class).longValue();
    }

    /**
     * 计算 token 的过期时刻（用于登录响应中的 expiresAt）。
     *
     * @param token JWT 字符串
     * @return 过期时刻
     * @throws JwtException 签名不合法时抛出
     */
    public Instant expiresAt(String token) {
        return parse(token).getExpiration().toInstant();
    }

    /** 有效期，供调用方计算 expiresAt */
    public Duration getExpireDuration() {
        return expireDuration;
    }
}
