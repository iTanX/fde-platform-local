package com.fde.probe;

import com.fde.workbench.common.result.ApiResult;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * CORS 的真实 HTTP 验收探针（main 方法即入口，<b>不使用 JUnit、不连数据库</b>）。
 * <p>
 * <b>为什么需要它</b>：CORS 预检的结果取决于「Servlet 容器与 Spring MVC 谁先接住 OPTIONS」，
 * MockMvc 的模拟容器在这点上与真实 Tomcat 不同，所以 CORS 的结论必须以真实 HTTP 为准。
 * 本类用 Spring Boot 起一个<b>只含 Web 层的真实 Tomcat</b>（排除数据源相关自动配置，
 * 不需要 FDE_DB_PASSWORD、也不访问任何数据库），装配与生产完全相同的过滤器链
 * （{@code WebSecurityFilterConfig} 的三个过滤器 + {@code CorsConfig} 的 CORS 过滤器，顺序一致），
 * 再发真实请求断言状态码与响应头。
 * <p>
 * <b>为什么放在 {@code com.fde.probe} 而不是 {@code com.fde.workbench} 或 src/test</b>：
 * 本类内含一个 {@code @SpringBootApplication} 配置类，而 Spring Boot 测试会把测试 classpath 上
 * <b>所有</b> {@code @SpringBootConfiguration} 都当作候选——放进测试源码或 {@code com.fde.workbench}
 * 包下，都会让既有 {@code @SpringBootTest} 报 "Found multiple @SpringBootConfiguration annotated classes"
 * （两个位置都实测过）。{@code com.fde.probe} 不在 {@code WorkbenchApplication} 的组件扫描
 * （{@code scanBasePackages = "com.fde.workbench"}）范围内，也不会被当作测试配置类。
 * 它只有一个 main 方法、不注册任何生产 Bean，也不会被生产代码调用。
 * <p>
 * 运行方式（在 workbench/ 目录）：
 * <pre>
 * mvn -q dependency:build-classpath -pl workbench-app -Dmdep.outputFile=/tmp/fde-cp.txt
 * java -cp "workbench-app/target/classes:$(cat /tmp/fde-cp.txt)" com.fde.probe.CorsE2eProbe
 * </pre>
 * 覆盖：
 * <ol>
 *   <li>预检 {@code OPTIONS /api/auth/login}（Origin=127.0.0.1:3080 + ACRM/ACRH）→ 2xx 且带完整 CORS 头；</li>
 *   <li>带 {@code Authorization} 的实际请求 → 响应带 {@code Access-Control-Allow-Origin}；</li>
 *   <li>非白名单来源的预检 → 被拒且无 CORS 头；</li>
 *   <li>不带 {@code Origin} 的同源请求 → 无 CORS 头（行为不变）。</li>
 * </ol>
 */
public final class CorsE2eProbe {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        SpringApplication app = new SpringApplication(ProbeApp.class);
        app.setDefaultProperties(java.util.Map.of(
                "server.port", "0",
                "server.address", "127.0.0.1",
                "spring.main.banner-mode", "off",
                "logging.level.root", "warn",
                "fde.workbench.storage-root", java.nio.file.Files.createTempDirectory("cors-probe").toString(),
                "fde.workbench.internal-token", "probe-internal-token-not-for-production"));
        try (ConfigurableApplicationContext ctx = app.run()) {
            int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) ctx)
                    .getWebServer().getPort();
            System.out.println("探针端口: " + port);
            runChecks("http://127.0.0.1:" + port);
        }
        if (failed > 0) {
            System.err.println("\nCORS 探针失败：" + failed + " 项");
            System.exit(1);
        }
        System.out.println("\nCORS 探针全部通过。");
    }

    /**
     * 对真实端口发请求并断言。
     *
     * @param base 探针服务根地址
     * @throws Exception HTTP 异常
     */
    private static void runChecks(String base) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // 1) 预检：来自 dsh web 的跨源 POST（浏览器登录前的第一步）
        HttpResponse<String> preflight = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://127.0.0.1:3080")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization")
                .build(), HttpResponse.BodyHandlers.ofString());
        check("预检 /api/auth/login 返回 2xx", preflight.statusCode() / 100 == 2,
                "实际 " + preflight.statusCode() + " body=" + preflight.body());
        check("预检带 Access-Control-Allow-Origin: http://127.0.0.1:3080",
                "http://127.0.0.1:3080".equals(preflight.headers().firstValue("Access-Control-Allow-Origin")
                        .orElse(null)),
                String.valueOf(preflight.headers().firstValue("Access-Control-Allow-Origin")));
        String allowMethods = preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        String allowHeaders = preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("");
        check("预检允许 POST", allowMethods.toUpperCase().contains("POST"), allowMethods);
        check("预检允许 content-type 与 authorization",
                allowHeaders.toLowerCase().contains("content-type") && allowHeaders.toLowerCase().contains("authorization"),
                allowHeaders);
        check("预检带 Access-Control-Max-Age",
                preflight.headers().firstValue("Access-Control-Max-Age").isPresent(),
                String.valueOf(preflight.headers().firstValue("Access-Control-Max-Age")));
        System.out.println("  预检响应头: " + preflight.headers().map());

        // 2) 实际请求带 Authorization（业务上是 401，但必须带 CORS 头）
        HttpResponse<String> actual = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"probe\",\"password\":\"\"}"))
                .header("Origin", "http://127.0.0.1:3080")
                .header("Authorization", "Bearer probe-invalid-token")
                .header("Content-Type", "application/json")
                .build(), HttpResponse.BodyHandlers.ofString());
        check("实际请求（带 Authorization）带 Access-Control-Allow-Origin",
                "http://127.0.0.1:3080".equals(actual.headers().firstValue("Access-Control-Allow-Origin")
                        .orElse(null)),
                "status=" + actual.statusCode() + " headers=" + actual.headers().map());
        System.out.println("  实际请求: status=" + actual.statusCode() + " ACAO="
                + actual.headers().firstValue("Access-Control-Allow-Origin").orElse("-"));

        // 3) 非白名单来源：浏览器来源必须是精确白名单，随便一个公网页面不许读响应
        HttpResponse<String> foreign = client.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://evil.example:3080")
                .header("Access-Control-Request-Method", "POST")
                .build(), HttpResponse.BodyHandlers.ofString());
        check("非白名单来源预检被拒（无 Access-Control-Allow-Origin）",
                foreign.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                "status=" + foreign.statusCode() + " ACAO="
                        + foreign.headers().firstValue("Access-Control-Allow-Origin").orElse("-"));

        // 4) 不带 Origin：同源 / 非浏览器请求行为不变
        HttpResponse<String> sameOrigin = client.send(HttpRequest.newBuilder(URI.create(base + "/api/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        check("不带 Origin 的请求照常 200 且无 CORS 头",
                sameOrigin.statusCode() == 200
                        && sameOrigin.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                "status=" + sameOrigin.statusCode());
    }

    /**
     * 断言并打印一行结果。
     *
     * @param label  说明
     * @param ok     是否通过
     * @param detail 实际值（失败时打印）
     */
    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            System.out.println("✓ " + label);
        } else {
            failed++;
            System.out.println("✗ " + label + " → " + detail);
        }
    }

    /**
     * 探针应用：只扫描 Web 层需要的组件，排除数据源与 SQL 初始化（不连库）。
     * <p>
     * 扫描范围刻意收窄到 {@code api} 包：{@code service} / {@code dao} 里的 Mapper、Service 都依赖数据库，
     * 与本探针无关（CORS 在进入 Controller 之前就已决定响应头）。
     */
    @SpringBootApplication(scanBasePackages = {
            "com.fde.workbench.api.config",
            "com.fde.workbench.api.handler",
            "com.fde.workbench.api.security"
    }, exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            SqlInitializationAutoConfiguration.class
    })
    static class ProbeApp {

        /**
         * 探针用的最小控制器：{@code /api/auth/login}（免登录）与 {@code /api/health}（免登录），
         * 足以验证两种预检结果。
         */
        @RestController
        static class ProbeController {

            /**
             * 登录探针端点（免登录）。
             *
             * @return 固定成功体
             */
            @PostMapping("/api/auth/login")
            public ApiResult<String> login() {
                return ApiResult.ok("probe");
            }

            /**
             * 健康探针端点（免登录）。
             *
             * @return 固定成功体
             */
            @GetMapping("/api/health")
            public ApiResult<String> health() {
                return ApiResult.ok("probe");
            }
        }
    }

    private CorsE2eProbe() {
    }
}
