package com.fde.workbench.api.config;

import com.fde.workbench.service.WorkbenchStorageProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Duration;
import java.util.List;

/**
 * 跨源（CORS）配置：让浏览器直连后端的 DSH Web 工作台页面能调通 {@code /api/**}。
 * <p>
 * <b>为什么必须有这一份（端到端阻塞问题实测）</b>：{@code dsh web} 在
 * {@code http://127.0.0.1:3080}、Java 后端在 {@code http://127.0.0.1:8081}，浏览器把两者视为
 * <b>不同源</b>。浏览器的预检请求 {@code OPTIONS /api/auth/login}（带
 * {@code Origin: http://127.0.0.1:3080}、{@code Access-Control-Request-Method: POST}）没有任何 CORS 响应头时，
 * Spring MVC（CorsUtils.isPreFlightRequest）直接返回 <b>403 "Invalid CORS request"</b>，
 * 于是页面里所有 {@code /api} 请求都变成 "Failed to fetch"——这是浏览器层面的拒绝，
 * 与后端是否认识这个用户无关。之前只能用"临时反代垫片"绕过的正是这一点。
 * <p>
 * <b>为什么在这里注册 {@link CorsFilter} 而不走
 * {@code WebMvcConfigurer#addCorsMappings}</b>：本工程的认证由三个 Servlet 过滤器完成
 * （{@link WebSecurityFilterConfig} 里的 JwtAuthFilter / LoginRequiredFilter / InternalAuthFilter），
 * 它们排在 Spring MVC 的 DispatcherServlet 之前。<b>必须让 CORS 处理排在认证之前</b>，否则会出现：
 * <ul>
 *   <li>预检 {@code OPTIONS} 请求没有 {@code Authorization} 头，先被 LoginRequiredFilter 当成"未登录"
 *       返回 401——浏览器拿不到 {@code Access-Control-Allow-*}，预检失败；</li>
 *   <li>被过滤器拦下的 401 / 403 响应上不会带 CORS 头，浏览器只会显示 "Failed to fetch"，
 *       前端连"401 请重新登录"这个业务信号都读不到（api.md §1.3 的 10101 跳转逻辑失效）。</li>
 * </ul>
 * {@link CorsFilter} 的 order 设成 {@link Ordered#HIGHEST_PRECEDENCE}，就在整条过滤器链最前面。
 * <p>
 * <b>安全口径（为什么不写 {@code allowedOrigins("*")}）</b>：
 * <ol>
 *   <li>允许来源从配置读（{@code fde.workbench.allowed-origins}），默认只放本机工作台的
 *       {@code http://127.0.0.1:3080} 与 {@code http://localhost:3080}，换端口/换机器由部署方显式加，
 *       不存在"默认对全网放开"；</li>
 *   <li>允许凭据（{@code allowCredentials=false}）：登录态是 {@code Authorization: Bearer} 头里的 JWT，
 *       不依赖 Cookie，因此不需要 {@code Access-Control-Allow-Credentials: true}——不放宽就是更小的面；</li>
 *   <li>允许请求头是<b>显式白名单</b>：{@code Content-Type / Authorization / Accept / Origin / X-Requested-With}。
 *       {@code X-Internal-Token} 是插件 Host 调 {@code /internal/**} 用的进程间令牌，
 *       <b>不列进白名单</b>：浏览器侧永远不该发它，即便有人硬塞，浏览器也会因为预检不通过而拦住。</li>
 *   <li>暴露的响应头只给前端真会读的 {@code Content-Disposition}（下载文件名）与
 *       {@code Retry-After}；其它响应头（如 {@code Set-Cookie}、内部诊断头）保持不可读。</li>
 *   <li>作用范围只有 {@code /api/*}：{@code /internal/*} 是插件 Host 的进程内调用（另受
 *       "仅本机 + X-Internal-Token"双重约束），不给任何浏览器来源开放 CORS。</li>
 * </ol>
 */
@Configuration
public class CorsConfig {

    /**
     * CORS 规则的作用范围：只覆盖对外的业务接口 {@code /api/**}。
     * <p>
     * <b>必须是 {@code /**} 而不是 {@code /*}</b>（真实 Tomcat 实测）：Spring 6.1 的
     * {@code UrlBasedCorsConfigurationSource} 内部按 {@code PathPattern} 匹配，而 {@code /*} 只匹配
     * <b>一个</b>路径段——{@code /api/health}、{@code /api/projects} 能命中，
     * {@code /api/auth/login}、{@code /api/projects/1} 却命中不到。结果是"只有最浅的几个接口能过预检，
     * 登录接口本身必然 403 'Invalid CORS request'"。{@code /**} 覆盖全部层级。
     */
    private static final String CORS_PATH_PATTERN = "/api/**";

    /**
     * 预检结果缓存时长：10 分钟。
     * <p>
     * 浏览器把"某来源 + 某方法 + 某组头"的预检结果缓存起来；10 分钟既能省掉绝大多数重复预检
     * （每次 {@code /api} 请求前都要预检一次的成本），也不会让"改了 allowed-origins 之后
     * 要等一小时才生效"成为排查障碍（重启后端本来就会失效，但前端页面可能挂着不刷新）。
     * 不用 {@code 24h} 这类长值的另一个理由：Chrome 对 {@code Access-Control-Max-Age} 有 2 小时上限，
     * 写再大也没有意义。
     */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    /** 允许的请求方法（docs/api.md：GET / POST / PUT / DELETE / PATCH；HEAD/OPTIONS 由 CORS 处理自带） */
    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "DELETE", "PATCH");

    /**
     * 允许的请求头（显式白名单）。
     * <ul>
     *   <li>{@code Content-Type}：{@code application/json}（除 POST 简单请求外都要预检）、
     *       {@code multipart/form-data}（输入文件上传）；</li>
     *   <li>{@code Authorization}：{@code Bearer <JWT>}（所有登录态请求）；</li>
     *   <li>{@code Accept} / {@code Origin}：fetch 默认会带，不列上会让预检失败；</li>
     *   <li>{@code X-Requested-With}：老式 XHR 兼容习惯，前端若带上也不该被拦。</li>
     * </ul>
     * <b>刻意不包含</b> {@code X-Internal-Token}（见类注释）。
     */
    private static final List<String> ALLOWED_HEADERS =
            List.of("Content-Type", "Authorization", "Accept", "Origin", "X-Requested-With");

    /** 允许前端读取的响应头：下载文件名与限流重试提示 */
    private static final List<String> EXPOSED_HEADERS = List.of("Content-Disposition", "Retry-After");

    /** 默认允许来源：本机 dsh web 的两种写法（{@code 127.0.0.1} 与 {@code localhost} 是不同源） */
    public static final List<String> DEFAULT_ALLOWED_ORIGINS =
            List.of("http://127.0.0.1:3080", "http://localhost:3080");

    private final WorkbenchStorageProperties properties;

    public CorsConfig(WorkbenchStorageProperties properties) {
        this.properties = properties;
    }

    /**
     * 纯 CORS 规则（不含 Servlet 注册），单独抽出来便于单测直接断言规则内容。
     *
     * @return CORS 配置源（只对 {@code /api/*} 生效）
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 逐个精确匹配：不用 "*"，也不混用 allowedOriginPatterns（混用会让规则语义变模糊）
        configuration.setAllowedOrigins(properties.resolvedAllowedOrigins());
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(EXPOSED_HEADERS);
        // 登录态在 Authorization 头里，不依赖 Cookie：不需要放开凭据
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(MAX_AGE);
        // 注意：不要设置 setAllowInitLookupPath(false)。实测（真实 Tomcat）：该开关会让 resolvePath 改走
        // "MVC 已缓存的查找路径"，而普通请求（非预检）在 Filter 阶段还没有这个缓存，规则就永远匹配不上，
        // 导致**所有 /api 预检都变成 403**。默认值（true）必须保持。
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(CORS_PATH_PATTERN, configuration);
        return source;
    }

    /**
     * 把 CORS 规则装成 Servlet 过滤器，并排在整个过滤器链最前面。
     * <p>
     * 必须最先执行的理由见类注释（预检不能被登录过滤器 401 掉，错误响应也要带 CORS 头）。
     * 用 {@link FilterRegistrationBean} 注册而不是给 {@link CorsFilter} 加 {@code @Component}：
     * 与 {@link WebSecurityFilterConfig} 一致，避免 Spring Boot 因"Bean 即过滤器"再自动注册一次。
     *
     * @param corsConfigurationSource CORS 规则
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsConfigurationSource corsConfigurationSource) {
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(corsConfigurationSource));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("corsFilter");
        return registration;
    }
}
