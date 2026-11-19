---
document: task-decomposition
status: in-progress
updated: "2026-11-19"
coordinator: "lead（只负责文档、拆任务、协调）"
executor: "subagents"
---

# Web 工作台 任务拆分与派发计划

> 本文是可执行的任务事实源。`lead` 负责定边界、写验收、派发、与用户确认；**所有代码由 subagent 实现**。任务完成状态以子代理回传的真实证据为准，未验证不勾选。
>
> **部署原则（2026-11-19 确认）**：本项目**自带一个可携带的 dsh**，不依赖也不修改用户本机已安装的 DeepSeek Harness；运行形态为**浏览器网页**（`dsh web`，默认 `http://127.0.0.1:3080`）。所有 dsh 数据、profile、端口均与用户环境隔离。详见 [architecture.md §13](architecture.md#13-自带可携带的-deepseek-harness部署原则)。

## 0. 协作规则

| 角色 | 职责 | 不做什么 |
|---|---|---|
| lead（我） | 维护 PRD/架构/接口/数据库文档；拆分任务与写验收标准；安排先后顺序与技术选型；汇总子代理结果；与用户确认关键决策 | 不亲自写业务代码 |
| 子代理 | 按任务卡的「目标 / 产出 / 验收命令 / 禁区」实现并自测，回传证据 | 不越界改其他任务的文件；不擅自改文档中的契约 |

### 0.1 每个任务卡的固定格式

```text
任务号 / 名称
依赖       前置任务号
目标       一句话结果
产出       具体文件与目录（相对仓库根）
约束       必须遵守的技术约束、文档契约、风格
禁区       明确禁止做的事情
验收       可执行命令 + 期望的观察结果（由子代理执行并贴出输出）
回传       完成时回报：改动文件清单、执行过的命令与结果、未完成项、发现的风险
```

### 0.2 派发波次

```text
波次 1（并行，互相无依赖）
  T0-1  Java 多模块骨架            ✅ 已完成
  T0-3  DSH 插件包骨架 + 构建打通   ✅ 产物已交付（安装验证推迟到自带 dsh）
  T0-4  自带可携带 dsh 的构建与启动脚本（新）

波次 2（依赖 T0-4 与 T1-1）
  T1-1  认证与项目中心后端        ← T0-1
  T1-3  工作台外壳与生成代码      ← T0-3
波次 3
  T1-2  成员与权限                ← T1-1
  T2-3  技能管理前端              ← T1-3, T2-1
  T2-1  技能管理后端              ← T1-2
波次 4
  T3-1  流程管理后端              ← T2-1
  T4-1  需求后端                  ← T3-1
  T3-3  流程管理前端              ← T2-3, T3-1
波次 5（核心）
  T5-1  节点后端（输入上传/节点信息/推进）
  T5-2  插件 Host：工具 + 内置技能 + prompt 段
  T5-3  插件 Client：节点页 + 会话嵌入 + 下载
  T5-4  插件 Client：项目/流程/技能/成员页面
波次 6
  T6-1  端到端联调收口
```

同一波次的任务可并行派发；跨波次必须等前置任务回传证据。

### 0.3 提交规则（按需提交，不堆积）

- **提交单位 = 一个完成且已验收的任务卡**，不做"一次提交全部"，也不做"每行都提交"。
- 每个任务验收通过后，只提交该任务卡「产出」中列出的文件；**同一提交不混入其他任务的文件**。
- 提交前必须：
  1. 子代理回传的证据（命令与输出）真实存在；
  2. `git status` 确认没有把无关文件带进提交（尤其是 `refer/`、`.idea/`、构建产物、含密码的本地配置）。
- `.gitignore` 已排除：`.idea/`、`refer/`、`target/`、`node_modules/`、`lib/`、`dist/`、日志。
- 数据库密码等敏感信息**永不入库**：代码从环境变量读取，仓库只保留 `.env.example` 这类占位示例。
- 提交信息格式（中文）：
  ```text
  <类型>(<范围>): <一句话结果>

  - 关键改动点
  - 验收方式
  ```
  示例：`feat(workbench): 完成 Java 多模块骨架与健康检查`
- 文档类改动（PRD/架构/任务卡）单独成一次提交，不与代码提交混合。
- 不修改已有提交历史（不 amend / 不 rebase 已推送内容），需要调整就追加新提交。

### 0.4 通信约定

- 后端与插件 Client 的接口契约以 [api.md](api.md) 为准；子代理改动契约前必须先报 lead，由 lead 更新文档。
- 前端所有文案走中文常量文件，禁止散落硬编码（便于后续国际化）。
- 每个子代理只负责自己任务卡内的目录，跨目录改动必须在回传中说明原因。

---

## 1. 工程结构与端口

```text
fde-platform-local/
├── workbench/                       # Java 后端（T0-1 创建）
│   ├── pom.xml
│   ├── workbench-common/
│   ├── workbench-dao/
│   ├── workbench-service/
│   ├── workbench-api/
│   └── workbench-app/
├── fde-workbench-bundle/            # DSH 插件（T0-3 创建）
│   ├── package.json
│   ├── cordis.patch.yml
│   ├── src/host/
│   ├── src/client/
│   ├── skills/workbench-node-delivery/SKILL.md
│   ├── locale/{en,zh}.json
│   └── icon.svg
└── docs/
```

| 服务 | 端口 | 说明 |
|---|---|---|
| MySQL | 192.168.2.125:3306 | 库 `fde_workbench` 已建好（5.7.44） |
| Java 后端 | 127.0.0.1:8081 | `/api/**` 用户接口，`/internal/**` 插件内部接口 |
| DSH Desktop | 现有实例 | 插件安装后从侧栏面板进入 |

---

## 2. 任务卡

### 波次 1

#### T0-1 Java 多模块骨架

- 依赖：无
- 目标：`mvn -q package` 成功，启动后 `GET /api/health` 返回 200 且能连上 MySQL 5.7。
- 产出：
  - `workbench/pom.xml`（parent，`spring-boot-starter-parent` 3.2.x，JDK 17）
  - `workbench-common/`：`ApiResult<T>`、`ErrorCode` 枚举（与 [api.md §1.3](api.md#13-错误码) 完全一致）、`BizException`、`PageResult<T>`、`JwtUtil`
  - `workbench-dao/`：MyBatis 3 + `mybatis-spring-boot-starter`，`pom` 引入 `mysql-connector-j`；JDBC URL 带 `sessionVariables=time_zone='%2B08:00'&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8`
  - `workbench-service/`、`workbench-api/`、`workbench-app/`（启动类、`application.yml`、`/api/health`）
  - `application.yml` 中 MySQL 连接：`jdbc:mysql://192.168.2.125:3306/fde_workbench`，账号 `root`，**密码从环境变量 `FDE_DB_PASSWORD` 读取**，仓库不得写死密码
- 约束：
  - 依赖方向 `app → api → service → dao → common`，不得反向
  - `application.yml` 中时间字段统一 JSR-310，`MapUnderscoreToCamelCase=true`
  - 不引入 Flyway/Liquibase（库已由 `docs/database/001_init.sql` 建好）
- 禁区：不改 `docs/database/*.sql`；不修改表结构；不引入前端代码
- 验收：
  ```bash
  export FDE_DB_PASSWORD=...            # 由用户提供或由 lead 转交
  cd workbench && mvn -q -DskipTests package
  java -jar workbench-app/target/*.jar &
  curl -s http://127.0.0.1:8081/api/health
  ```
  期望：`{"code":0,"data":{"db":"ok","version":"..."}}`，进程日志无 SQL 异常
- 回传：模块清单、`mvn` 输出结尾、`curl` 输出、启动日志片段

#### T0-3 DSH 插件包骨架 + 会话嵌入冒烟

- 依赖：无
- 目标：插件安装后侧栏面板列表第一行出现「Web 工作台」，点击打开 `main` 面板；**面板内能渲染一个真实 DSH 会话**（可输入、可滚动）。
- 产出：`fde-workbench-bundle/`
  - `package.json`（`dsh.bundle.patch` + `dsh.client`，`files` 覆盖 `src`、`locale`、`icon.svg`）
  - `cordis.patch.yml`（插入 Host 行与 Client 行）
  - `src/host/index.ts`（`apply` 空实现 + `export const name`，先保证可加载）
  - `src/client/index.ts`：`sidebar.panellist`（`order:-100`）+ `main` keyed 面板注册
  - `src/client/main/WorkbenchMain.tsx`：外壳页（标题栏 + 内容占位）
  - `src/client/session/NodeSessionView.tsx`：**会话嵌入冒烟**，见 [plugin-integration.md §4.1.1](plugin-integration.md#411-节点页里的会话如何嵌进来整页方案核心)
  - `locale/{en,zh}.json`、`icon.svg`
- 约束：
  - 构建方式必须先实测确定：优先用 DSH 源码 checkout（`/tmp/dsh-src`，版本 `dsh-v0.2.2-rc.5`）内的构建工具链产出 `lib/client.js`；若不可行，如实回报并给出替代方案，**不得伪造可运行产物**
  - 只使用官方公开扩展点，不 patch DSH 源码
  - 冒烟用例要有失败态：会话拿不到时显示错误与「新建会话」，不留白屏
- 禁区：不实现业务接口调用；不写业务数据表逻辑
- 验收：
  ```bash
  # 构建（方式以实测为准）
  # 安装（Full access）
  # 然后：DSH 侧栏首行出现「Web 工作台」→ 点击 → 面板打开 → 面板内可见一个真实会话
  ```
  期望：面板内消息流可见、输入框可输入并可发送、面板可滚动；`Esc Esc` 停止仍生效；禁用插件后 DSH 恢复原样
- 回传：构建命令与输出、安装与启用结果（`plugin_manager` 的 `list_plugins` 行状态）、冒烟截图或文案描述、遇到的问题与结论
- 风险提示：这是**最高不确定性**任务，必须先打通"构建 + 加载 + 会话嵌入"三件事再谈其他。

### 波次 1.5

#### T0-4 自带可携带 dsh 的构建与启动（新增）

- 依赖：T0-3（插件产物可加载）、T0-1（后端可启动）
- 目标：在一台**未安装 DeepSeek Harness** 的电脑上（需要 Node ≥ 24.9 与网络），执行一条 `setup-runtime` 完成构建、再一条 `start-all` 启动即可用浏览器访问；用户本机已有 DSH 时互不干扰。
- 分发方式（已确认）：**目标机现场构建**，仓库不携带 `runtime/` 与构建产物。
- 产出：
  - `scripts/setup-runtime.sh` / `.ps1`：按固定 tag（`dsh-v0.2.2-rc.5`）拉取或使用本地副本构建自带 dsh，产出 `runtime/dsh`；生成 `runtime/profile`（bundles = 官方 web bundle + `fde-workbench-bundle`）；安装插件依赖
  - `scripts/start-all.sh` / `.ps1`：以 `DSH_HOME=<repo>/runtime/dsh-home`、独立端口启动 **`dsh web`**（浏览器形态，默认 `http://127.0.0.1:3080`）；同时拉起 Java 后端；启动成功后打印可直接点击的访问地址；任一失败给出明确原因
  - `docs/runtime.md`：目录约定、目标机前置条件（Node/pnpm/网络/MySQL）、首次构建步骤、启动、升级方式、换机器部署步骤、常见故障（离线、端口占用、授权链接）
  - `.gitignore` 增加 `runtime/`
- 约束：
  - **绝不写入 `~/.dsh`**；不读取用户本机 DSH 的任何数据
  - 端口可配，默认 dsh `3080`、后端 `8081`，与用户本机 DSH（如 57320）不冲突
  - 脚本跨 macOS/Linux/Windows，路径不硬编码到某台机器
  - 构建产物不入库；未验证项必须如实标注
- 禁区：不改 DSH 官方源码；不把插件装进用户全局 profile；不提交 `runtime/`
- 验收（必须在**不影响**用户本机 Desktop 的前提下执行）：
  ```bash
  ./scripts/setup-runtime.sh          # 成功产出 runtime/dsh 与 profile
  ./scripts/start-all.sh &            # dsh 与后端同时起来
  curl -s http://127.0.0.1:8081/api/health          # db=ok
  curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3080   # 200（dsh web 页面可达）
  ```
  期望：浏览器打开 `http://127.0.0.1:3080` 可进入 DSH Web 界面且侧栏出现「Web 工作台」；用户本机 `~/.dsh` 与 Desktop 应用数据**内容无变化**（可用 `ls -la` 前后对比、或比对文件数量）
- 回传：构建耗时与体积、启动日志、两个端口探测结果、`~/.dsh` 未被写证明、搬运可行性结论（单目录大小、是否依赖全局 node_modules）

> 现状参考：Desktop 安装包内的 `app.asar` 是归档文件，其内 node_modules 不能直接作为可搬运运行时；参考项目 `refer/FF-FDE-Delivery-Workbench/upstream/deepseek-harness` 有完整源码与 `corepack pnpm` 构建流程，可作起点。版本须与插件目标版本一致（`0.2.2-rc.5`）。

### 波次 2

#### T1-1 认证与项目中心（后端）

- 依赖：T0-1
- 目标：注册/登录/JWT/项目 CRUD 全部可用，列表只返回我所属的项目。
- 产出（`workbench-dao|service|api` 对应包）：
  - `entity/User, Project, ProjectMember`、`mapper/*.xml`
  - `AuthService`（注册：用户名 3–32 位字母数字下划线、密码可空；登录：用户名不存在返回 `10102` 且消息为"用户不存在，请先注册"；BCrypt）、`JwtAuthFilter`、`CurrentUser` 上下文
  - `ProjectService`（创建时同事务写 `wb_project_member(role=owner)`；列表按成员过滤；编辑/删除仅 owner，`revision` 乐观锁）
  - `ProjectController`、`AuthController`
- 约束：严格对齐 [api.md §2、§3](api.md)；所有时间字段由应用写入（`LocalDateTime.now()`），DB 列无默认值
- 验收：
  ```bash
  curl -s -XPOST :8081/api/auth/register -d '{"username":"demo","password":""}'
  curl -s -XPOST :8081/api/auth/login -d '{"username":"demo","password":""}'      # 拿到 token
  curl -s -XPOST :8081/api/auth/login -d '{"username":"nobody","password":"x"}'   # 10102 用户不存在
  curl -s -XPOST :8081/api/projects -H "Authorization: Bearer $T" -d '{"name":"P1"}'
  curl -s :8081/api/projects -H "Authorization: Bearer $T"
  ```
  期望：注册→登录→建项目→列表只含自己的项目；另一用户登录后列表为空
- 回传：接口逐条 curl 结果、表数据截图（`SELECT id,username FROM wb_user` 等）

#### T1-3 工作台外壳与接口封装

- 依赖：T0-3
- 目标：面板内具备登录页 → 项目中心 → 项目四 Tab 的完整壳，接口层可用。
- 产出（`fde-workbench-bundle/src/client/`）：
  - `api/client.ts`（fetch 封装：base URL `http://127.0.0.1:8081`、JWT 注入、统一错误码处理）
  - `api/*.ts`（auth/project/skill/workflow/requirement/node/session 各模块，方法签名与 [api.md](api.md) 一致）
  - `store/app-store.ts`（页面栈、当前用户、当前项目）
  - `pages/LoginPage, ProjectsPage, ProjectPage`（四 Tab 骨架与空状态）
  - `ui/`（Tab、卡片、空状态、加载态、错误态、确认对话框）
- 约束：文案走中文常量文件；样式用 CSS Modules + `--dsw-alias-*` 令牌；不引入第三方 UI 库
- 验收：登录 demo/demo2 两个账号进入项目中心；未登录访问被拦到登录页；后端未启动时显示可重试错误态而非白屏
- 回传：页面路径与交互说明、错误态处理说明

### 波次 3

#### T1-2 成员与权限（后端）

- 依赖：T1-1
- 目标：成员增删、owner 转让、越权统一 403。
- 产出：
  - `ProjectMemberService`（列表/添加/移除/转让，转让与移除按 [api.md §4](api.md) 的事务顺序）
  - `ProjectAccessAspect` 或等价拦截（所有带 `projectId` 的 service 方法校验成员）
  - `@RequireOwner` 注解或显式判定；数据归属校验工具
- 验收：非成员访问 403；非 owner 改项目 403；转让后原 owner 失去管理权；移除成员后其在该项目的技能被清理、其需求导致移除失败并回滚
- 回传：权限矩阵逐条验证结果

#### T2-1 技能管理（后端）

- 依赖：T1-2
- 目标：上传 zip、版本递增、下载、预览、删除前引用校验。
- 产出：`SkillService`（解包校验 `SKILL.md`、算 `files_json`/`checksum`、按版本重打包下载、单文件预览、引用校验：`wb_workflow_node_skill` + 未完成实例 `skills_snapshot`）、`SkillController`
- 约束：文件落盘路径按 [database.md §5.5](database.md#55-文件存储路径约定)；上传先写临时文件再原子 rename；路径越界必须拒绝
- 验收：上传含 `SKILL.md` 的 zip → v1；再传 → v2；下载 v1/v2 内容不同；无 `SKILL.md` → 400；被引用时删除 → 409 并返回引用清单
- 回传：构造的测试 zip 与逐条结果

#### T2-3 技能管理（前端）

- 依赖：T1-3、T2-1
- 目标：技能 Tab 的列表、上传、版本抽屉、下载、删除与引用提示。
- 产出：`pages/skills/*`（列表、上传对话框、版本抽屉、删除引用提示）
- 验收：与 T2-1 的接口逐条对应；错误态与空状态可见
- 回传：交互截图或步骤说明

### 波次 4

#### T3-1 流程管理（后端）

- 依赖：T2-1
- 目标：流程 CRUD + `PUT /nodes` 整体保存（增删改重排、输入输出、多技能）。
- 产出：`WorkflowService`（整表 diff 保存、`seq` 重排、`itemKey` 校验 `^[a-z0-9][a-z0-9-]{0,63}$`、技能归属校验、删除引用校验）、`WorkflowController`
- 验收：新增 3 节点拖到第 1 位保存后刷新顺序一致；多技能保存回显；`itemKey` 重复 400；被需求引用的流程删除 409
- 回传：逐条 curl + 表数据

#### T4-1 需求与节点实例（后端）

- 依赖：T3-1
- 目标：需求 CRUD，创建时同事务生成节点实例快照，进度查询。
- 产出：`RequirementService`（快照 `inputs_snapshot`/`outputs_snapshot`/`skills_snapshot`、workspace 路径生成、`current_seq` 维护）、`RequirementController`
- 约束：快照写入后，修改流程不得影响已有需求（验证用例必须覆盖）
- 验收：创建后节点状态正确；改流程后旧需求不变；他人编辑 403
- 回传：逐条结果 + 前后快照对比

#### T3-3 流程管理（前端）

- 依赖：T2-3、T3-1
- 目标：流程列表 + 节点列表（上下拖动排序、点击展开）、输入/输出项编辑、多技能选择。
- 产出：`pages/workflows/*`
- 验收：拖拽后保存顺序与界面一致；展开编辑可增删输入输出项与技能；保存失败有可见错误
- 回传：交互说明

### 波次 5（核心）

#### T5-1 节点后端

- 依赖：T4-1
- 目标：节点信息页所需全部接口 + 输入上传 + 输出登记与推进事务 + 文件下载 + 会话登记。
- 产出：
  - `NodeService`（`GET /api/requirements/{rid}/nodes/{nid}` 含 `previousNode`/`readonly`/`defaultSessionId`；上传输入；下载）
  - `NodeAdvanceService`（`/internal/nodes/{nid}/outputs/{itemKey}/commit`：校验 token、路径、sha256；UPSERT 输出；判定必填项齐备并推进；返回 `advanced`/`remainingRequiredOutputs`）
  - `SessionService`（`/api/requirements/{rid}/sessions` 列表与新建登记、`/internal/sessions`、`touch`、默认会话解析）
  - `InternalAuthFilter`（`X-Internal-Token` + 仅 `127.0.0.1`）
  - 文件存储抽象（原子写、sha256、路径越界拒绝）
- 约束：推进必须在单事务内完成；半提交绝不推进；`readonly` 判定严格按 [PRD AC-F4/F5/H5](PRD.md)
- 验收：
  ```bash
  # 用 demo 的 token：
  # 1) 上传自由输入 → 2) 模拟模型写文件并调 /internal/.../commit ×2
  # 3) 第二次提交后 GET 节点 → 已进入下一节点
  # 4) 用 demo2 的 token GET 该节点 → readonly=true
  ```
- 回传：逐条 curl、表数据、推进前后节点状态

#### T5-2 插件 Host：工具 + 内置技能 + prompt 段

- 依赖：T0-3、T5-1
- 目标：模型能在真实会话里完成"读上下文 → 读引用 → 写输出 → 提交登记 → 节点推进"。
- 产出（`fde-workbench-bundle/src/host/`）：
  - 4 个工具：`node_context_get`、`node_reference_read`、`node_output_write`、`node_output_commit`（schema 见 [plugin-integration.md §5.2](plugin-integration.md#52-工具设计)）
  - 内置技能 `workbench-node-delivery`：`skills/workbench-node-delivery/SKILL.md`，用 `ctx.skills.register({ name, description, whenToUse, source, content })` 注册（字段以官方 `SkillRegistration` 为准）
  - `systemPrompt.section` 一行节点上下文，仅在 cwd 属于需求 workspace 时出现
  - 配置 `Config`：`javaBaseUrl`、`internalToken`、`storageRoot`、`maxOutputBytes`（不硬编码）
- 约束：工具描述从模型视角书写；失败返回可读错误；不写执行细节
- 验收：在 DSH 会话中说"帮我产出需求条目清单"→ 模型依次调用工具 → Java 侧出现 `wb_requirement_node_output` 记录 → 节点推进；重复同一轮，模型能正确读取上一节点输出
- 回传：会话记录片段、工具调用参数与结果、数据库记录

#### T5-3 插件 Client：节点页 + 会话嵌入 + 下载

- 依赖：T0-3、T5-1
- 目标：节点页两个视图（会话 / 节点信息）在同一页面内，输出可下载、输入可上传、全部完成后自动进入下一节点。
- 产出（`fde-workbench-bundle/src/client/`）：
  - `pages/RequirementPage`（视图页签、只读态、返回导航）
  - `session/NodeSessionView`（`sessions.retain` + `SessionProvider` + `conversation.content`，见 §4.1.1）、`SessionController`（默认会话解析、新建会话、失效提示）
  - `node/NodeInfoPanel`（上一节点输出 / 输入 / 输出项 / 说明）
  - 上传控件、下载按钮、提交后的自动刷新
- 验收：对应 [PRD AC-F4.1~F4.7、F5.1、F7.1~F7.5](PRD.md) 逐条通过
- 回传：逐条验收结果

#### T5-4 插件 Client：项目/流程/技能/成员页面

- 依赖：T1-3、T2-3、T3-3
- 目标：项目内四 Tab 全部可用（需求列表 + 新建需求）。
- 产出：`pages/requirements/*`、`pages/members/*`，其余页面联调
- 验收：与 PRD B/C/D/E 组验收逐条对应
- 回传：逐条结果

### 波次 6

#### T6-1 端到端联调收口

- 依赖：T5-1 ~ T5-4
- 目标：从注册到需求完成的完整主流程一次走通，并留下记录。
- 产出：
  - `docs/verification/2026-11-19-e2e-walkthrough.md`（真实操作步骤、观察结果、失败与修复）
  - `README.md` 的启动与安装章节
  - DSH 版本兼容记录（实测版本、面板入口实际位置、会话嵌入冒烟结论）
- 验收：新注册账号 → 建项目 → 上传技能 → 建流程 → 建需求 → 上传输入 → 对话产出 → 下载 → 推进 → 完成
- 回传：完整走查记录链接与结论

---

## 3. 依赖与并行视图

| 任务 | 依赖 | 可并行 |
|---|---|---|
| T0-1 | — | 与 T0-3 |
| T0-3 | — | 与 T0-1 |
| T0-4 | T0-3、T0-1 | — |
| T1-1 | T0-1 | |
| T1-3 | T0-3 | 与 T1-1 |
| T5-2 | T0-4 提供运行环境 | — |
| T1-2 | T1-1 | |
| T2-1 | T1-2 | |
| T2-3 | T1-3、T2-1 | |
| T3-1 | T2-1 | |
| T3-3 | T2-3、T3-1 | |
| T4-1 | T3-1 | 与 T3-3 |
| T5-1 | T4-1 | |
| T5-2 | T0-3、T5-1 | 与 T5-3、T5-4 |
| T5-3 | T0-3、T5-1 | 与 T5-2、T5-4 |
| T5-4 | T1-3、T2-3、T3-3 | 与 T5-2 |
| T6-1 | T5-1~T5-4 | — |

---

## 4. 完成状态

> 只有子代理回传真实执行证据后才勾选。

| 任务 | 状态 | 证据 |
|---|---|---|
| T0-1 | ✅ 完成（提交 `10a393c`） | `mvn package` 通过；`/api/health` = `db=ok`；错误密码降级为 `19901/db=down`；提交前 `git diff --cached` 无明文密码 |
| T0-3 | ✅ 完成（提交 `545ae95`） | 构建可复现；浏览器产物外部依赖仅 react；替身 ctx 注册出 locale/工厂/main/侧栏四项；**安装与界面验证未执行**（改为装入自带 dsh 后验证） |
| T0-4 | 未开始 | — |
| T1-1 | ✅ 完成（提交 `9888845`） | `mvn clean install` 12 例全绿（lead 独立复跑）；curl 覆盖权限矩阵与乐观锁；时区断言 `+08:00`；8081 已释放 |
| T1-2 | ✅ 完成（提交 `c6c23c0`） | `mvn clean install` 18 例全绿（lead 独立复跑）；curl 覆盖越权、过期 revision、移除回滚与项目 1 还原 |
| T1-3 | 未开始 | — |
| T2-1 | 未开始 | — |
| T2-3 | 未开始 | — |
| T3-1 | 未开始 | — |
| T3-3 | 未开始 | — |
| T4-1 | 未开始 | — |
| T5-1 | 未开始 | — |
| T5-2 | 未开始 | — |
| T5-3 | 未开始 | — |
| T5-4 | 未开始 | — |
| T6-1 | 未开始 | — |

---

## 5. 待确认

| # | 项 | 影响 |
|---|---|---|
| C1 | ~~插件构建方式~~ 已确认：官方 `tsdown.client.ts` 的 `clientBundle` 预设，在 DSH 源码 checkout 副本内构建 | — |
| C6 | ~~自带 dsh 的运行形态~~ 已确认：浏览器网页（`dsh web`） | — |
| C2 | 后端启动时数据库密码如何提供（环境变量 / 本地配置文件） | T0-1 |
| C3 | 是否需要给用户提供 Docker 一键启动 | T6-1 |
| C4 | ~~git 提交~~ 已确认：按任务卡粒度提交 | — |
| C5 | 原始需求文档含明文数据库密码，已替换为占位并清理历史 | 已处理，`docs/原始需求/…:74` |
| C7 | DELETE 的 revision 传参方式 | 已定稿：查询参数 `?revision=N`（[api.md §1.2](api.md)） |
| C8 | owner 判定是否双源校验 | 已定稿：`owner_id` 与成员表角色必须同时一致（[api.md §4](api.md)） |
| C9 | 移除成员的引用完整性靠外键还是显式校验 | 已定稿：**显式校验**。实际外键 `fk_requirement_creator` 指向 `wb_user` 而非成员关系，外键拦不住 |
