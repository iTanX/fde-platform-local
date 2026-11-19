# workbench（Java 后端）

Web 工作台的 Java 后端，Maven 多模块，依赖方向单向：`app → api → service → dao → common`。

| 模块 | 一句话职责 |
|---|---|
| `workbench-common` | 统一返回体、错误码、业务异常、分页结构、JWT 工具 |
| `workbench-dao` | MyBatis Mapper、Entity 与数据源配置 |
| `workbench-service` | 领域服务、事务边界、权限判定 |
| `workbench-api` | Controller、DTO、全局异常处理 |
| `workbench-app` | 启动类、application.yml、可执行 jar |

构建与运行（数据库密码只从环境变量读取）：

```bash
export FDE_DB_PASSWORD='<本地联调密码>'
mvn -q -DskipTests package
java -jar workbench-app/target/workbench-app-*.jar
curl -s http://127.0.0.1:8081/api/health
```

## 跨源（CORS）：浏览器直连后端

`dsh web` 默认在 `http://127.0.0.1:3080`，本后端在 `http://127.0.0.1:8081`，浏览器视为**不同源**。
后端用 `fde.workbench.allowed-origins` 声明允许来源（默认 `http://127.0.0.1:3080` 与
`http://localhost:3080`），由 `CorsConfig` 注册成排在过滤器链最前面的 `CorsFilter`，规则只覆盖 `/api/**`：

- 规则模式必须是 `/api/**`。`/api/*` 在 Spring 6.1 的 `PathPattern` 语义下只匹配**一个**路径段，
  `/api/auth/login`、`/api/projects/1` 这类多层路径会匹配不到，预检直接 403 `Invalid CORS request`（实测踩过）。
- 允许头是显式白名单（`Content-Type / Authorization / Accept / Origin / X-Requested-With`），
  `X-Internal-Token` **不在**其中（它只给插件 Host 的进程内调用）。
- 换端口启动 dsh web 时追加来源：`FDE_WORKBENCH_ALLOWED_ORIGINS='http://127.0.0.1:3081,http://localhost:3081'`
  （逗号分隔，**整体覆盖** `application.yml` 的默认项；Spring 的宽松绑定匹配不到这个名字，由
  `WorkbenchStorageProperties#resolvedAllowedOrigins` 显式读取）。

`/internal/**` 不开放 CORS（插件进程内调用，另受 `X-Internal-Token` + 仅本机约束）。

验证（不需要数据库）：

```bash
# 1) 单元 + 真实过滤器链的 HTTP 测试（16 项，不连库）
mvn -q test -pl workbench-app -Dtest='CorsConfigTest,CorsFilterIntegrationTest'
# 2) 真实 Tomcat 探针：起一个只含 Web 层的 Spring Boot（排除数据源），发真实预检与实际请求
mvn -q dependency:build-classpath -pl workbench-app -Dmdep.outputFile=/tmp/fde-cp.txt
java -cp "workbench-app/target/classes:$(cat /tmp/fde-cp.txt)" com.fde.probe.CorsE2eProbe
```
