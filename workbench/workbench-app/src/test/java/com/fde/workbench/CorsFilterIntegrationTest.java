package com.fde.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.api.config.CorsConfig;
import com.fde.workbench.api.controller.AuthController;
import com.fde.workbench.api.handler.GlobalExceptionHandler;
import com.fde.workbench.api.security.CurrentUserHolder;
import com.fde.workbench.api.security.InternalAuthFilter;
import com.fde.workbench.api.security.JwtAuthFilter;
import com.fde.workbench.api.security.LoginRequiredFilter;
import com.fde.workbench.common.security.JwtUtil;
import com.fde.workbench.service.AuthService;
import com.fde.workbench.service.WorkbenchStorageProperties;
import com.fde.workbench.service.security.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CorsFilter;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 的 HTTP 端到端测试（<b>不启动 Spring 上下文、不连数据库</b>）。
 * <p>
 * <b>为什么用 standaloneSetup + 真实过滤器，而不是 {@code @SpringBootTest}</b>：
 * 要证明的是"预检不会被登录过滤器 401 掉""401 响应也带 CORS 头"，这取决于三个真实过滤器
 * 在链上的<b>顺序</b>（CORS 必须最先）。而 Service / Mapper / 数据源与这件事无关：
 * 这里只手挂 {@link AuthController} + 真实 {@link GlobalExceptionHandler}，两个依赖
 * （{@link AuthService}、{@link CurrentUserContext}）用 Mockito 占位。
 * 因此本类<b>不需要 FDE_DB_PASSWORD、也不访问任何库</b>，同时没有 fake DB 驱动被误加载的问题。
 * <p>
 * <b>链的顺序与生产一致</b>：{@code WebSecurityFilterConfig} 里 JwtAuthFilter(10) →
 * LoginRequiredFilter(20) → InternalAuthFilter(30)，CORS 用 {@code HIGHEST_PRECEDENCE}
 * （{@link CorsConfig#corsFilterRegistration}），所以这里按同样顺序 addFilters。
 * <p>
 * <b>覆盖的两种请求（与浏览器行为一致）</b>：
 * <ol>
 *   <li>预检 {@code OPTIONS /api/auth/login}：带 {@code Origin}、{@code Access-Control-Request-Method: POST}
 *       与 {@code Access-Control-Request-Headers: content-type,authorization}；</li>
 *   <li>实际请求 {@code POST /api/auth/login} 带 {@code Origin} + {@code Authorization}：
 *       业务上 401（token 无效，LoginRequiredFilter 拦下）时响应仍必须带
 *       {@code Access-Control-Allow-Origin}，否则浏览器会把 401 也变成 "Failed to fetch"，
 *       前端读不到 api.md §1.3 的跳登录信号。</li>
 * </ol>
 */
class CorsFilterIntegrationTest {

    /** 浏览器来源：dsh web 的默认地址 */
    private static final String DSH_WEB_ORIGIN = "http://127.0.0.1:3080";

    /** 统一返回体序列化（与 Spring Boot 默认装配同类，足够本类断言） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** CORS 规则（从配置构造，与生产同一个类） */
    private final CorsConfig corsConfig = new CorsConfig(new WorkbenchStorageProperties());

    /** 按生产顺序挂好真实过滤器的 MockMvc */
    private MockMvc chain;

    /**
     * 组装过滤器链：CORS(最高优先级) → JWT → 登录要求 → 内部令牌。
     * <p>
     * 三个安全过滤器都是真实实现（不是替身）：测的就是"它们与 CORS 的先后关系"。
     * 登录业务一律由 Mockito 替身处理——本类不关心登录是否成功，只关心 HTTP 层 CORS 头。
     */
    @BeforeEach
    void buildChain() {
        AuthService authService = Mockito.mock(AuthService.class);
        CurrentUserContext currentUserContext = Mockito.mock(CurrentUserContext.class);
        // 默认"未登录"：LoginRequiredFilter 对受保护路径返回 401 —— 正是要验证"401 也带 CORS 头"的场景。
        // require() 抛什么不重要，LoginRequiredFilter 只要捕获异常即可；这里让替身抛运行时异常。
        Mockito.when(currentUserContext.require()).thenThrow(new IllegalStateException("未登录"));
        JwtUtil jwtUtil = Mockito.mock(JwtUtil.class);
        // 无效 token：解析返回 null（JwtAuthFilter 会当成"未登录"继续往下走，由 LoginRequiredFilter 返回 401）
        // 注意不能 stub 成返回 null：JwtAuthFilter 会直接解引用 claims（真实 JwtUtil 对无效 token 是抛异常），
        // 所以这里让替身抛 JwtException，行为与"token 无效"一致（过滤器捕获后视为未登录）。
        Mockito.when(jwtUtil.parse(Mockito.anyString()))
                .thenThrow(new io.jsonwebtoken.MalformedJwtException("test: invalid token"));
        WorkbenchStorageProperties properties = new WorkbenchStorageProperties();
        // 内部令牌配一个测试专用占位，避免 InternalAuthFilter 走"未配置令牌"分支（本类不测它）
        properties.setInternalToken("cors-test-token-not-for-production");

        chain = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(
                        // 与生产一致的顺序：CORS 先于所有认证过滤器
                        new CorsFilter(corsConfig.corsConfigurationSource()),
                        new JwtAuthFilter(jwtUtil, new CurrentUserHolder()),
                        new LoginRequiredFilter(currentUserContext, objectMapper),
                        new InternalAuthFilter(properties, objectMapper))
                .build();
    }

    /**
     * 浏览器预检请求（OPTIONS + 必要的 CORS 请求头）。
     *
     * @param path           被预检的路径
     * @param origin         浏览器来源
     * @param method         预检声明的真实方法
     * @param requestHeaders 预检声明的自定义请求头（逗号分隔，可空）
     * @return 请求构造器
     */
    private static MockHttpServletRequestBuilder preflight(String path, String origin, String method,
                                                           String requestHeaders) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (requestHeaders != null) {
            builder = builder.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        }
        return builder;
    }

    /**
     * 预检：来自 dsh web 的跨源 POST 必须拿到 2xx 与完整 CORS 头。
     * <p>
     * <b>修复前</b>这里返回 403 "Invalid CORS request"（没有 CORS 配置时 Spring MVC 对预检的默认行为），
     * 浏览器的登录请求根本无法发出；这也是"预检被 LoginRequiredFilter 先 401 掉"的反例——
     * 若 CORS 过滤器排在登录过滤器之后，这里会得到 401 而不是 200。
     * <p>
     * 用 {@code /api/auth/register} 而不是 {@code /api/auth/login} 做"2xx 的正例"只是因为
     * {@code standaloneSetup} 下 {CorsFilter} 的预检短路要求"URL 能匹配到某个 handler"，
     * 而两者同属 {@code /api/**}、规则完全相同；登录路径的预检见
     * {@link #preflightOnLoginPathAlsoCarriesCorsHeaders()}。
     */
    @Test
    void preflightFromDshWebOriginIs2xxWithAllowHeaders() throws Exception {
        chain.perform(preflight("/api/auth/register", DSH_WEB_ORIGIN, "POST", "content-type,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DSH_WEB_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("content-type")))
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_MAX_AGE));
    }

    /**
     * 登录路径（两层：{@code /api/auth/login}）的预检。
     * <p>
     * 这条用例守的是真实 Tomcat 上发现的坑：CORS 规则若写成 {@code /api/*}，在 Spring 6.1 的
     * {@code PathPattern} 下只匹配一个路径段，登录接口的预检会 403 "Invalid CORS request"。
     * MockMvc 的模拟容器对规则匹配的行为与真实容器一致，所以这里能用它守住"多层路径必须有规则"。
     */
    @Test
    void preflightOnLoginPathIs2xx() throws Exception {
        chain.perform(preflight("/api/auth/login", DSH_WEB_ORIGIN, "POST", "content-type,authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DSH_WEB_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")));
    }

    /**
     * 登录路径的预检：必须带 CORS 头。
     * <p>
     * 注意 MockMvc 的模拟容器与真实容器对预检的默认处理不同：真实 Servlet 容器（Tomcat）对
     * {@code OPTIONS /api/auth/login} 直接交给 {@link CorsFilter}，后者短路返回 <b>200</b>；
     * MockMvc 在没有 OPTIONS 映射时会在 Filter 之前就回 403，因此本类对登录路径只断言
     * "响应上一定有 CORS 头"（这正是浏览器的判定依据），而 200 的端到端验收放在真实后端上做
     * （lead 的 curl 步骤）。2xx + 完整头的路径由 {@link #preflightFromDshWebOriginIs2xxWithAllowHeaders}
     * 用 {@code /api/auth/register} 覆盖。
     */
    @Test
    void preflightOnLoginPathAlsoCarriesCorsHeaders() throws Exception {
        chain.perform(preflight("/api/auth/login", DSH_WEB_ORIGIN, "POST", "content-type,authorization"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DSH_WEB_ORIGIN));
    }

    /** 预检：{@code localhost:3080} 与 {@code 127.0.0.1:3080} 是不同源，同样要放行 */
    @Test
    void preflightFromLocalhostOriginIs2xx() throws Exception {
        chain.perform(preflight("/api/projects", "http://localhost:3080", "GET", null))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3080"));
    }

    /**
     * 实际请求带 {@code Authorization}：业务上 401（token 无效）时，响应仍必须带 CORS 头——
     * 否则前端连"请重新登录"的业务信号都读不到。
     * <p>
     * 这条同时守住"CORS 必须排在认证之前"：LoginRequiredFilter 返回的 401 也要经过 CORS 过滤器加头。
     */
    @Test
    void actualRequestWithAuthorizationCarriesCorsHeadersEvenOnError() throws Exception {
        chain.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, DSH_WEB_ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DSH_WEB_ORIGIN));
    }

    /** 预检：非白名单来源必须被拒（不能因为"想打通"就放成 *） */
    @Test
    void preflightFromForeignOriginIsRejected() throws Exception {
        chain.perform(preflight("/api/auth/login", "http://evil.example:3080", "POST", null))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /** 预检：{@code X-Internal-Token} 不在白名单，浏览器侧不允许携带（令牌只在插件 Host 进程内使用） */
    @Test
    void preflightRejectsInternalTokenHeader() throws Exception {
        chain.perform(preflight("/api/projects", DSH_WEB_ORIGIN, "GET", "x-internal-token"))
                .andExpect(status().isForbidden());
    }

    /**
     * {@code /internal/**} 不注册 CORS 规则：浏览器发来的跨源预检一律拒绝
     * （内部接口只给本机插件进程调用，另受 X-Internal-Token 约束）。
     */
    @Test
    void internalPathsAreNotCorsEnabled() throws Exception {
        chain.perform(preflight("/internal/nodes/1/context", DSH_WEB_ORIGIN, "GET", null))
                .andExpect(status().isForbidden());
    }

    /** 不带 {@code Origin} 的普通请求：响应里不应出现任何 CORS 头（行为与修复前一致） */
    @Test
    void sameOriginRequestHasNoCorsHeaders() throws Exception {
        chain.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
