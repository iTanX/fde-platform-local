package com.fde.workbench;

import com.fde.workbench.api.config.CorsConfig;
import com.fde.workbench.service.WorkbenchStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 规则的纯单元测试（<b>不启动 Spring 上下文、不连数据库</b>）。
 * <p>
 * 为什么单独成类而不是并进 {@code @SpringBootTest}：CORS 是 Servlet 过滤器的行为，
 * 规则本身（允许谁、允许什么方法 / 头、预检缓存多久）是纯数据，用 {@link CorsConfigurationSource}
 * 直接断言即可；凡是不需要 HTTP 端到端的部分都不该拖一个数据库上下文进来。
 * 真正的"OPTIONS 预检 + 带 Authorization 的实际请求"由同样的规则驱动，
 * 另有 {@link CorsFilterIntegrationTest}（不依赖数据库）覆盖端到端。
 */
class CorsConfigTest {

    /** 从配置构造一份 CORS 规则（与生产装配走同一个 {@link CorsConfig}） */
    private static CorsConfigurationSource source(List<String> origins) {
        WorkbenchStorageProperties properties = new WorkbenchStorageProperties();
        if (origins != null) {
            properties.setAllowedOrigins(origins);
        }
        return new CorsConfig(properties).corsConfigurationSource();
    }

    /** 模拟浏览器对某个路径发起的 CORS 请求（用于取出匹配到的规则） */
    private static CorsConfiguration configFor(CorsConfigurationSource source, String origin, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        // MockHttpServletRequest 不会自己推 servletPath / contextPath（都是空串），而 UrlBasedCorsConfigurationSource
        // 用 "contextPath + servletPath" 去匹配注册的 "/api/*"；真实容器里这两个字段一定已填好。
        // 这里显式补上，否则规则永远匹配不到（这正是本类第一版失败的原因）。
        request.setServletPath(path);
        request.addHeader("Origin", origin);
        return source.getCorsConfiguration(request);
    }

    @Test
    void defaultPropertiesAllowBothLoopbackOriginsOfDshWeb() {
        // 默认值就是 dsh web 的两种写法：127.0.0.1 与 localhost 在浏览器里是不同源，缺一个就有一半场景打不通
        assertEquals(List.of("http://127.0.0.1:3080", "http://localhost:3080"),
                new WorkbenchStorageProperties().resolvedAllowedOrigins());
    }

    @Test
    void preflightFromDshWebOriginIsAllowedWithAuthorizationHeader() {
        CorsConfigurationSource source = source(null);
        // 路径用一条常规业务路径：规则注册在 "/api/*"（Ant 风格，不多层匹配），/api/projects 正好命中一层
        CorsConfiguration config = configFor(source, "http://127.0.0.1:3080", "/api/projects");
        assertNotNull(config, "配置必须命中 /api/projects");
        assertEquals("http://127.0.0.1:3080", config.checkOrigin("http://127.0.0.1:3080"),
                "127.0.0.1:3080 必须被允许（浏览器预检来源）");
        assertEquals("http://localhost:3080", config.checkOrigin("http://localhost:3080"),
                "localhost:3080 也必须被允许");
        // Authorization + Content-Type 是登录与 JSON 提交的核心头：少一个，预检就会 403
        assertNotNull(config.checkHeaders(List.of("authorization", "content-type", "accept")));
        // 方法：登录 / 上传 / 修改 / 删除都要能过预检
        // 预检声明的只会是真实业务方法（浏览器自己处理 OPTIONS，不需要它出现在白名单里）
        for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH")) {
            // checkHttpMethod 返回"允许的方法集合"，非 null 就表示该方法能过预检
            assertNotNull(config.checkHttpMethod(HttpMethod.valueOf(method)), "预检方法必须允许：" + method);
        }
        // 预检缓存：非空且不超过浏览器上限（Chrome 2 小时）
        assertEquals(TimeUnit.MINUTES.toSeconds(10), config.getMaxAge(), "预检缓存 10 分钟");
        // 登录态在 Authorization 头里，不依赖 Cookie：不放开凭据
        assertFalse(Boolean.TRUE.equals(config.getAllowCredentials()));
    }

    @Test
    void internalTokenHeaderIsNotExposedToBrowsers() {
        CorsConfiguration config = configFor(source(null), "http://127.0.0.1:3080", "/api/projects");
        assertNotNull(config);
        // X-Internal-Token 只给插件 Host 的进程内调用，浏览器侧永远不该发；不列入白名单时预检会拦住它
        assertNull(config.checkHeaders(List.of("x-internal-token")),
                "X-Internal-Token 不得出现在允许请求头里");
    }

    @Test
    void foreignOriginsAreRejected() {
        CorsConfigurationSource source = source(null);
        CorsConfiguration config = configFor(source, "http://evil.example:3080", "/api/projects");
        assertNotNull(config);
        // 任意公网网页、以及"只是端口不同"的本机服务，都不是白名单来源
        assertNull(config.checkOrigin("http://evil.example:3080"));
        assertNull(config.checkOrigin("http://127.0.0.1:3081"), "换端口不算同一来源，必须显式配置");
        assertNull(config.checkOrigin("http://127.0.0.1:9999"));
    }

    /**
     * 规则必须覆盖 <b>多层</b> 路径（{@code /api/auth/login}、{@code /api/projects/1}）。
     * <p>
     * 这是真实 Tomcat 上踩到的坑：{@code UrlBasedCorsConfigurationSource} 内部按 {@code PathPattern} 匹配，
     * {@code /api/*} 只匹配<b>一个</b>路径段，于是 {@code /api/health}、{@code /api/projects} 能过预检，
     * 而登录接口 {@code /api/auth/login} 永远 403 "Invalid CORS request"——浏览器根本发不出登录请求。
     * 因此这里对每一层路径都断言"能取到规则"，防止有人把 {@code /**} 改回 {@code /*}。
     */
    @Test
    void ruleCoversNestedApiPathsNotJustOneSegment() {
        CorsConfigurationSource source = source(null);
        for (String path : List.of("/api/health", "/api/projects", "/api/auth/login",
                "/api/projects/1", "/api/requirements/12/nodes/34/advance-preview")) {
            assertNotNull(configFor(source, "http://127.0.0.1:3080", path),
                    "CORS 规则必须命中多层路径：" + path);
        }
    }

    /**
     * 环境变量 {@code FDE_WORKBENCH_ALLOWED_ORIGINS} 覆盖配置（逗号分隔）。
     * <p>
     * 这条用例只能在"确实设置了该环境变量"的子进程里跑（见 {@code CorsEnvOverrideTest}）：
     * JUnit 进程里改不了自己的环境变量。
     */
    static List<String> resolvedFromEnv(List<String> configured) {
        WorkbenchStorageProperties properties = new WorkbenchStorageProperties();
        properties.setAllowedOrigins(configured);
        return properties.resolvedAllowedOrigins();
    }

    @Test
    void internalPathsHaveNoCorsRuleAtAll() {
        // /internal/** 只给插件 Host 本机调用：规则里根本没有注册它，浏览器请求不会被加上任何 CORS 头
        assertNull(configFor(source(null), "http://127.0.0.1:3080", "/internal/nodes/1/context"),
                "/internal/* 不得有 CORS 规则");
    }

    @Test
    void allowedOriginsComeFromConfigurationAndAreNormalized() {
        // 部署方换端口时的写法：显式追加；尾斜杠与空白必须被归一化（浏览器 Origin 头永远不带尾斜杠）
        CorsConfiguration config = configFor(
                source(List.of(" http://127.0.0.1:3099/ ", "", "  ", "http://127.0.0.1:3099")),
                "http://127.0.0.1:3099", "/api/projects");
        assertNotNull(config);
        assertEquals(List.of("http://127.0.0.1:3099"), config.getAllowedOrigins(),
                "空白项被丢弃、尾斜杠被去掉、重复项合并");
        assertEquals("http://127.0.0.1:3099", config.checkOrigin("http://127.0.0.1:3099"));
        assertNull(config.checkOrigin("http://127.0.0.1:3080"), "配置覆盖默认值后，默认来源不再放行");
    }
}
