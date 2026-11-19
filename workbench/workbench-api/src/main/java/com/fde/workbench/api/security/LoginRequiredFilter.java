package com.fde.workbench.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.security.CurrentUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 登录要求过滤器：除放行清单外的 {@code /api/**} 一律要求已登录，否则返回 401 + code 10101。
 * <p>
 * 为什么放在过滤器而不是每个 Controller 里：PRD AC-H1.4 要求"访问任意业务接口都返回 401"，
 * 集中在一处可以保证后续新增接口默认受保护（默认拒绝，显式放行），不会漏配。
 * <p>
 * 放行清单：{@code /api/auth/register}、{@code /api/auth/login}、{@code /api/health}（本任务约定的三条）。
 * 后续内部接口 {@code /internal/**} 不属于 {@code /api/**}，不会经过本过滤器（T5-1 另有内部 token 校验）。
 */
public class LoginRequiredFilter extends OncePerRequestFilter {

    /** 免登录路径（精确匹配） */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/health"
    );

    /** 受保护的业务路径前缀 */
    private static final String API_PREFIX = "/api/";

    private final CurrentUserContext currentUserContext;

    private final ObjectMapper objectMapper;

    public LoginRequiredFilter(CurrentUserContext currentUserContext, ObjectMapper objectMapper) {
        this.currentUserContext = currentUserContext;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!requiresLogin(path)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            // 仅判断"有没有有效身份"，具体权限判断在 Service
            currentUserContext.require();
        } catch (RuntimeException ex) {
            writeUnauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 判定该路径是否需要登录：只保护 {@code /api/**} 且不在免登录清单内。
     *
     * @param path 不含 contextPath 的请求路径
     * @return true 需要登录
     */
    static boolean requiresLogin(String path) {
        if (!path.startsWith(API_PREFIX) && !path.equals("/api")) {
            return false;
        }
        String normalized = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
        return !PUBLIC_PATHS.contains(normalized);
    }

    /** 直接写统一返回体：401 / 10101，前端据此跳转登录页（api.md §1.3） */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResult<Void> body = ApiResult.fail(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getDefaultMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
