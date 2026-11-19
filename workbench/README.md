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
