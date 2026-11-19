package com.fde.workbench.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.common.security.JwtUtil;
import com.fde.workbench.api.security.CurrentUserHolder;
import com.fde.workbench.api.security.JwtAuthFilter;
import com.fde.workbench.api.security.LoginRequiredFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet 过滤器装配：认证（解析 JWT）+ 登录要求（默认拒绝、显式放行）。
 * <p>
 * 两个过滤器都只用 {@link FilterRegistrationBean} 注册（不声明为 @Component），避免 Spring Boot
 * 因"Bean 即过滤器"而重复注册一次。顺序：JwtAuthFilter（order=10）先解析身份，
 * LoginRequiredFilter（order=20）再判定是否必须登录。
 */
@Configuration
public class WebSecurityFilterConfig {

    /** JWT 认证过滤器顺序：先于登录要求过滤器 */
    private static final int ORDER_AUTH = 10;

    /** 登录要求过滤器顺序：后于认证过滤器 */
    private static final int ORDER_LOGIN_REQUIRED = 20;

    /**
     * 注册 JWT 认证过滤器。
     *
     * @param jwtUtil           JWT 工具
     * @param currentUserHolder 当前用户上下文
     * @return 过滤器注册（作用范围 {@code /*}）
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtUtil jwtUtil,
                                                                         CurrentUserHolder currentUserHolder) {
        FilterRegistrationBean<JwtAuthFilter> registration =
                new FilterRegistrationBean<>(new JwtAuthFilter(jwtUtil, currentUserHolder));
        registration.addUrlPatterns("/*");
        registration.setOrder(ORDER_AUTH);
        registration.setName("jwtAuthFilter");
        return registration;
    }

    /**
     * 注册"必须登录"过滤器：未登录访问 {@code /api/**} 返回 401 + 10101。
     *
     * @param currentUserHolder 当前用户上下文
     * @param objectMapper      统一返回体序列化
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<LoginRequiredFilter> loginRequiredFilterRegistration(
            CurrentUserHolder currentUserHolder, ObjectMapper objectMapper) {
        FilterRegistrationBean<LoginRequiredFilter> registration =
                new FilterRegistrationBean<>(new LoginRequiredFilter(currentUserHolder, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(ORDER_LOGIN_REQUIRED);
        registration.setName("loginRequiredFilter");
        return registration;
    }
}
