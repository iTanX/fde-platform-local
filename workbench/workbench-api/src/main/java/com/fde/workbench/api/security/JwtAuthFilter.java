package com.fde.workbench.api.security;

import com.fde.workbench.common.security.JwtUtil;
import com.fde.workbench.service.bo.CurrentUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：解析 {@code Authorization: Bearer <token>} 并写入当前用户上下文。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>本任务只做"认证"（我是谁），不做"授权"（能不能做），细粒度权限由 Service 判定、T1-2 再加统一拦截；</li>
 *   <li>token 缺失或无效时**不在这里直接返回 401**，而是不写上下文继续往下走，
 *       让免鉴权路径（register/login/health）照常工作，受保护路径由 Service 的 {@code require()} 抛 10101；</li>
 *   <li>解析失败统一记 debug 日志且不打印 token 原文，避免日志泄漏凭证。</li>
 * </ul>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** Bearer 前缀（大小写不敏感比较） */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    private final CurrentUserHolder currentUserHolder;

    public JwtAuthFilter(JwtUtil jwtUtil, CurrentUserHolder currentUserHolder) {
        this.jwtUtil = jwtUtil;
        this.currentUserHolder = currentUserHolder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null) {
                CurrentUser user = parseUser(token);
                if (user != null) {
                    // 写入 ThreadLocal：Service 层通过 CurrentUserContext#require() 读取
                    currentUserHolder.set(user);
                }
            }
            chain.doFilter(request, response);
        } finally {
            // 无论业务是否异常都要清理，防止线程复用串号
            currentUserHolder.clear();
        }
    }

    /**
     * 从请求头取 token。
     *
     * @param request HTTP 请求
     * @return token 字符串；未携带返回 null
     */
    static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 校验并解析 token。
     *
     * @param token JWT
     * @return 当前用户；token 无效/过期返回 null（视为未登录）
     */
    private CurrentUser parseUser(String token) {
        try {
            Claims claims = jwtUtil.parse(token);
            Long userId = claims.get(JwtUtil.CLAIM_USER_ID, Number.class) == null
                    ? null : claims.get(JwtUtil.CLAIM_USER_ID, Number.class).longValue();
            String username = claims.get(JwtUtil.CLAIM_USERNAME, String.class);
            if (userId == null || username == null) {
                return null;
            }
            // displayName 在登录响应里给出；请求上下文只需要身份标识，展示名由 /api/auth/me 实时查询
            return new CurrentUser(userId, username, username);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT 校验失败：{}", ex.getMessage());
            return null;
        }
    }
}
