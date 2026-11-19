---
document: architecture
status: draft
updated: "2026-11-19"
---

# Web 工作台 架构设计

> **架构版本 v2（2026-11-19 修订）**：由"独立 SPA + 跨源 iframe"改为**DSH 插件内原生页面**。业务界面以 React 组件形式注册进 DSH 的 `main` 面板；节点页与 DSH 会话同页共存，用户无需离开工作台。原 v1 的 iframe/postMessage 设计作废，决策过程见 §11 AD-1。

## 1. 架构目标

| 目标 | 度量 |
|---|---|
| 与 DeepSeek Harness 解耦 | DSH 升级后，业务前后端零改动，只需重装插件 |
| 数据以项目为顶层维度 | 所有业务表都能由 `project_id` 定位 |
| 会话由 DSH 负责 | Java 后端不出现模型调用、不解析消息内容、不存对话正文 |
| 产出可追溯 | 每个输出项有版本、摘要、提交人与时间 |

## 2. 总体结构

```text
┌──────────────────── DeepSeek Harness（官方安装，未修改） ────────────────────┐
│  fde-workbench-bundle                                                        │
│  ├── Host 半                                                                 │
│  │    ├── 4 个模型工具（node_context_get / node_reference_read /             │
│  │    │   node_output_write / node_output_commit）                            │
│  │    ├── 内置指导技能（ctx.skills.register）                                 │
│  │    ├── 节点上下文 system prompt 段（ctx.systemPrompt.section）             │
│  │    └── 2 条 Host 路由（会话解析 / 业务回调）                                │
│  └── Client 半（React 插件）                                                  │
│       ├── sidebar.panellist  「Web 工作台」入口（面板列表第一行）              │
│       ├── main (keyed)       工作台整页（业务页面全部为 React 组件）            │
│       │    ├── 项目中心 / 项目四 Tab / 需求进度                                │
│       │    └── 节点页：会话视图 + 节点信息视图（同一页面内切换）                 │
│       └── 会话嵌入     官方 conversation.content 工厂 + SessionProvider        │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │ REST + JWT（fetch，同机）
┌───────────────────────────────▼──────────────────────────────────────────────┐
│  workbench-server（Java 17 + Spring Boot 3）                                  │
│  ├── workbench-app     启动、配置、过滤器链                                    │
│  ├── workbench-api     Controller / DTO / 参数校验                            │
│  ├── workbench-service 业务事务、权限判定、推进规则                             │
│  └── workbench-dao     MyBatis Mapper / Entity                                │
│  依赖：MySQL(fde_workbench) · 文件存储根                                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

**关键变化**：不再有 iframe、不再有 postMessage 桥、不再有独立部署的业务 SPA。业务前端作为 DSH Client 插件的源码存在，由 DSH 的 Vite 构建产出一并通过 `main` 面板渲染。Java 后端退化为纯数据与文件服务（含文件下载、上传），不再承担任何界面职责。

三条运行链路：

| 链路 | 参与者 | 说明 |
|---|---|---|
| L1 业务读写 | 插件 Client ↔ Java | 全部业务数据、文件上传下载（JWT 鉴权） |
| L2 对话与产出 | DSH 原生会话 ↔ Host 工具 ↔ Java 内部接口 | 模型产出并登记；界面由 DSH 会话组件渲染 |
| L3 会话定位 | 插件 Client/Host ↔ Java | 解析/创建/恢复会话与节点绑定 |

### 2.1 会话如何嵌在节点页里（已核实官方能力）

节点页的「会话」视图**不是 iframe，也不是跳转**，而是复用 DSH 官方的可复用会话工厂：

```text
节点页「会话」视图
  ├─ ctx.sessions.retain(sessionId)        ← 任意事务会话（含需求主会话）取得引用
  ├─ <SessionProvider session={ref}>       ← 官方 ui-slots 标准席位，声明会话作用域
  │    └─ renderFactorySlot('conversation.content',
  │          { variant: 'embedded', phase, hero },
  │          { slots: { views: 会话视图选择器 } })
  └─ 输入框、消息流、工具卡片、运行状态全部由 DSH 渲染
```

依据（`dsh-v0.2.2-rc.5` 源码）：

- `conversation.content` 是官方声明的可复用工厂（`ui-conversation/src/client/apply.ts`），`ui-subagent` 已经用它把任意子会话渲染进右侧栏（`ui-subagent/src/client/sidebar-chat/index.tsx`）。
- `sessions.retain(target: SessionTarget)` 接受任意 `SessionId`（`contract/sessions.ts: SessionTarget = SessionId | SubagentAddress`），不限于子会话。
- 工厂自带的 `views` 局部槽位允许嵌入方决定渲染哪个会话视图（默认 Chat）；「节点信息」则由工作台自己渲染，二者的切换在节点页顶部完成。

### 2.2 节点页整体布局

```text
┌─ DSH 侧栏 ─┬────────────── 工作台 main 面板 ─────────────────────────────┐
│ 新建会话    │ [← 返回项目]  需求标题 · 状态 · 节点 2/3                     │
│ 全局面板    │ ┌ 会话 ┬ 节点信息 ┐                                       │
│  Web 工作台 │ │  DSH 原生会话组件  │ 上一节点输出 / 节点输入 / 输出项      │
│  插件/定时  │ │  消息流 + 输入框   │ 输出项下载 · 上传                      │
│ 会话列表    │ └───────────────────┴─────────────────────────────────────┘│
│ 设置        │                                                             │
└─────────────┴─────────────────────────────────────────────────────────────┘
```

## 3. 模块划分（后端）

### 3.1 Maven 多模块

```text
workbench-parent (pom)
├── workbench-common      统一返回体、错误码、JWT 工具、异常基类、文件存储抽象
├── workbench-dao         Entity、Mapper（MyBatis XML）、乐观锁与逻辑删除约定
├── workbench-service     领域服务 + 事务边界 + 权限校验
├── workbench-api         Controller、DTO、全局异常处理、参数校验
└── workbench-app         启动类、application.yml、安全过滤器、静态资源托管
```

依赖方向单向：`app → api → service → dao → common`，**不允许反向**。

### 3.2 三层职责

| 层 | 负责 | 不负责 |
|---|---|---|
| Controller（`workbench-api`） | 参数校验、DTO 转换、HTTP 语义 | 事务、权限规则、业务判断 |
| Service（`workbench-service`） | 事务边界、权限判定、推进规则、文件校验 | SQL、HTTP |
| Mapper（`workbench-dao`） | SQL、结果映射 | 业务规则 |

### 3.3 横切关注点

| 关注点 | 实现 |
|---|---|
| 鉴权 | `JwtAuthFilter` 解析 token → 写入 `CurrentUser` 上下文；`/api/auth/**` 放行 |
| 项目成员校验 | `ProjectAccessAspect` 拦截带 `projectId` 的 Service 方法；非成员抛 403 |
| 数据归属校验 | Service 内显式判定"是否为创建者 / 是否 owner" |
| 乐观锁 | `revision` 字段随更新 SQL 一起参与 `WHERE`，影响行数为 0 抛 `40901` |
| 逻辑删除 | MyBatis 拦截或 SQL 显式 `deleted=0` |
| 统一返回 | `ApiResult<T>{ code, message, data }` |
| 全局异常 | `@RestControllerAdvice`，把业务异常映射为统一错误码 |
| 内部接口 | `/internal/**` 由独立过滤器校验 `X-Internal-Token`，且仅在 `127.0.0.1` 绑定生效 |

## 4. 前端结构（DSH Client 插件）

业务前端不再独立部署，而是作为插件包 `fde-workbench-bundle/src/client/` 的源码，随 DSH 构建产物一起加载。

```text
fde-workbench-bundle/
├── src/
│   ├── host/                       # Host 半（见 §7）
│   └── client/
│       ├── index.ts                # apply：注册面板入口、main 面板、会话控制器
│       ├── WorkbenchPanelIcon.tsx  # 侧栏入口图标（参考 ui-schedule 的 TaskManagerIcon）
│       ├── main/                   # main 面板根组件与路由状态
│       │   ├── WorkbenchMain.tsx   # 页面栈：项目中心 / 项目 / 需求 / 节点
│       │   ├── ProjectPage.tsx     # 项目四 Tab
│       │   └── RequirementPage.tsx # 需求节点页（会话 / 节点信息 两个视图）
│       ├── session/                # 会话嵌入（官方 conversation.content 工厂）
│       │   ├── NodeSessionView.tsx # SessionProvider + conversation.content
│       │   └── SessionController.ts# retain / 新建 / 默认会话解析
│       ├── node/                   # 节点信息视图
│       │   └── NodeInfoPanel.tsx   # 上一节点输出 / 输入 / 输出 / 说明
│       ├── api/                    # Java 后端请求封装（fetch + JWT）
│       ├── store/                  # 页面栈与数据缓存（应用内快照 store）
│       └── ui/                     # GitHub 风格基础组件（列表、Tab、表单、文件卡）
├── host/ → ../src/host
├── skills/workbench-node-delivery/SKILL.md
├── locale/{en,zh}.json
└── cordis.patch.yml
```

### 4.1 视觉规范（GitHub 风格）

| 元素 | 规则 |
|---|---|
| 布局 | 页头（返回/标题/操作）+ 视图页签 + 内容区（最大宽度 1280，居中） |
| 颜色 | 语义色使用 DSH 主题令牌 `--dsw-alias-*`（自动跟随明暗）；GitHub 风格用于**版式与密度**（6px 圆角、1px 分隔线、紧凑行高） |
| 字体 | 系统字体栈；正文 14px；路径与代码用等宽字体 |
| Tab | 顶部下划线 Tab，选中项加粗并显示下边框（对应原始需求"类似 GitHub 切 Code/Issues/PR"） |
| 状态 | 节点状态用带色点徽章：未开始(灰)/进行中(蓝)/已完成(绿) |
| 约束 | 不引入组件库；控件样式沿用 DSH 主题令牌，避免与宿主界面割裂（阶段 0 做一次视觉对齐后冻结） |

### 4.2 需求节点页结构

```text
[← 返回项目]  需求标题 · 状态徽章 · 当前节点 2/3
[ 会话 | 节点信息 ]                     ← 只有当前节点且我为创建者时出现「会话」
───────────────────────────────────────────────────────
「会话」视图：DSH 原生会话（消息流 + 输入框）
   顶部会话条：本需求会话切换器 · 当前节点绑定标识 · [新建会话]
「节点信息」视图（自上而下）：
  1. 节点名称 + 节点说明
  2. 上一节点输出（文件卡片 + 下载）
  3. 节点输入
       自由输入：标题右侧 [上传]；下方列出已上传文件
       固定输入：逐项一行，每行右侧 [上传]；列出该项已上传文件
  4. 输出项（逐项一行：[名称] [说明] [状态] [下载]）
  5. 只读态：非当前节点 / 非创建人 → 无「会话」页签、无上传按钮，下载仍可用
```

### 4.3 会话视图的工作方式

- 进入节点页时默认打开「会话」视图（对齐参考图），会话由 `SessionController` 解析：
  1. 节点 `primary_session_id`；
  2. 该需求最近活跃且未归档的会话；
  3. 都没有 → 通过 Host 路由创建会话并登记，再 retain 渲染。
- 渲染使用官方工厂（见 §2.1），`variant: 'embedded'` 保证不渲染全屏 Hero，尺寸填满视图容器。
- 「新建会话」在视图顶部操作，创建后自动登记到当前节点并切换到新会话。
- 会话状态（运行中、等待审批、工具调用）全部由 DSH 会话组件原生展示，业务端不重复实现。

## 5. 数据流

### 5.1 需求创建

```text
POST /api/projects/{pid}/requirements { title, description, workflowId }
 → 事务：
   1) 校验流程属于项目且存在节点
   2) 插入 wb_requirement（current_seq=1，生成 workspace 路径）
   3) 按流程节点顺序插入 wb_requirement_node（快照输入/输出/技能及版本）
   4) 第一个节点 status=current，写 started_at
   5) 首次需要会话时再创建；此时不创建
 → 返回需求详情
```

### 5.2 上传节点输入

```text
POST /api/requirements/{rid}/nodes/{nid}/inputs (multipart)
 → 校验：我是需求创建人；该节点为当前节点
 → 校验 input_type 与 file_kind；fixed 必须带 inputItemId
 → 写文件到 inputs/<seq>/；插入 wb_file、wb_requirement_node_input
 → 返回文件信息
```

### 5.3 模型产出与推进

见 [plugin-integration.md §7](plugin-integration.md#7-产出如何显示在页面上端到端链路)。Java 侧的推进事务：

```text
POST /internal/nodes/{nid}/outputs/{itemKey}/commit { relativePath, sha256, operator }
 → 校验 X-Internal-Token；校验文件存在、sha256 一致、路径在本节点输出目录内
 → 事务：
   1) UPSERT wb_requirement_node_output（version_no+1）+ 新 wb_file
   2) 计算 required 输出项是否齐备、required 固定输入项是否齐备
   3) 齐备 → 当前节点 done；下一节点 current（primary_session_id 沿用需求默认会话）
              否则 → 全部 done 时需求 status=completed
   4) 返回 { committed, versionNo, advanced, nextNodeName, remainingRequiredOutputs }
```

### 5.4 会话定位与恢复

```text
节点页「会话」视图挂载
 → SessionController.resolve(requirementId, nodeInstanceId)
   1) node.primary_session_id（数据库已绑定）
   2) 需求下 archived=0 且 last_active_at 最大
   3) 都没有 → Host 路由 POST /api/fde-workbench/session/create
               → uiWorkspace.connectWorkspace(ws) 得到 sessionId
               → 回调 POST /internal/sessions 登记（写节点绑定 + 弱关联）
 → ctx.sessions.retain(sessionId) 取得 SessionReference
 → <SessionProvider> + conversation.content 渲染
```

- 会话在节点之间**默认沿用**：节点推进时下一节点不复制旧绑定，进入新节点时按上面第 2 条命中同一会话，历史连续可见。
- 用户在节点页点「新建会话」：走第 3 步创建、绑定当前节点，视图立即切换到新会话。
- 会话失效（DSH 侧已删除）：`retain` 失败 → 后端标记 `archived=1` → 视图提示「会话已失效」并给出「新建会话」，**不伪造历史**。

## 6. 文件存储

| 项 | 约定 |
|---|---|
| 存储根 | `fde.workbench.storage-root`（默认 `./data`） |
| 目录结构 | 见 [database.md §5.5](database.md#55-文件存储路径约定) |
| 写入方式 | 先写临时文件 → 计算 sha256 → 原子 rename |
| 下载 | 后端 `GET /api/files/{fileId}/download`，校验成员权限后以 `Content-Disposition: attachment` 返回 |
| 大小限制 | 默认单文件 20 MiB，可通过配置调整 |
| 越界防护 | 所有相对路径解析后必须仍在存储根内 |

## 7. DSH 集成点汇总

| 能力 | 只依赖 | 依据（官方源码） |
|---|---|---|
| 侧栏入口 | `sidebar.panellist`（`order: -100`，面板列表第一行、会话列表之上）+ `main` keyed 面板 | `ui-sidebar/src/client/SidebarRoot.tsx` |
| 工作台整页 | `main` keyed 面板渲染 React 组件（**无 iframe、无覆盖层**） | `ui-layout/src/client/index.ts` 的 `main` keyed 声明 |
| 会话嵌入 | `sessions.retain` + `<SessionProvider>` + `conversation.content` 工厂（`variant: 'embedded'`） | `ui-subagent/src/client/sidebar-chat/index.tsx` 为同一用法的官方先例 |
| 新建/恢复会话 | `uiWorkspace.connectWorkspace` / `uiWorkspace.openSession` | `ui-workspace/src/client/navigation.ts` |
| 模型工具 | `ctx.tools` | 不改 agent loop |
| 技能 | `ctx.skills.register` | 不改技能加载器 |
| 节点上下文 | `ctx.systemPrompt.section` | 不拼装整个 prompt |
| 后端回调 | `ctx.webServer.register`（Host 路由，`createWebRequestTrust` 校验） | `dsh-fde-source` 等官方插件同款 |

**明确不依赖**：不 patch 官方源码、不修改任何 DSH 包、不读取 DSH 私有存储文件、不使用 iframe 或 postMessage。

详见 [plugin-integration.md](plugin-integration.md)。

## 8. 失败与降级

| 场景 | 行为 |
|---|---|
| Java 后端未启动 | 工作台面板显示"无法连接工作台后端（127.0.0.1:8081）"+重试；DSH 其余功能正常 |
| 会话渲染失败（retain 异常） | 「会话」视图区内显示错误与「新建会话」，不影响「节点信息」视图 |
| 会话已失效 | 后端标记 archived，视图提示新建；不伪造历史 |
| 插件某条目加载失败 | 面板入口显示错误态；不阻断 DSH 原生面板与会话 |
| 输出摘要不一致 | 拒绝登记，返回 400；页面保持"待补充" |
| 并发推进 | 乐观锁 + 事务；冲突返回 40901，页面提示刷新重试 |
| 文件写入失败 | 事务回滚，不产生登记记录 |

## 9. 非功能设计

| 类别 | 设计 |
|---|---|
| 安全 | JWT（HS256，默认 12h）；密码 BCrypt；`/internal/**` 预共享密钥 + 仅本机绑定；上传文件类型与大小校验；无 SQL 拼接 |
| 性能 | 列表分页（默认 20）；节点详情一次性返回整条流程；文件下载走流式 |
| 可观测 | 统一请求日志（traceId）；内部接口调用日志；Agent 工具失败写业务日志 |
| 部署 | `docker-compose.yml`（mysql + server）可选；最少只需一个 MySQL 与一个 Java 进程 |

## 10. 配置项

`application.yml` 关键配置：

```yaml
fde:
  workbench:
    storage-root: ./data
    internal-token: ${FDE_INTERNAL_TOKEN:change-me}
    max-upload-bytes: 20971520
    node-output-max-bytes: 10485760
    # 插件 Client 运行在 DSH origin 下，需要放行 DSH 的 origin（本机桌面/Web 默认同一 origin）
security:
  jwt:
    secret: ${FDE_JWT_SECRET:...}
    expire-hours: 12
dsh:
  workbench:
    base-url: http://127.0.0.1:8081
```

## 11. 技术决策记录

| # | 决策 | 备选 | 理由 |
|---|---|---|---|
| AD-1 | **业务页做成 DSH 插件页面（v2，已确认）** | v1 的独立 SPA + 跨源 iframe | 跨源 iframe 无法嵌入 DSH 会话组件，点「会话」必须跳转，体验不可接受；改为插件页面后节点页内直接对话。代价：业务前端与 DSH 客户端契约耦合，升级 DSH 时需回归 |
| AD-7 | 会话用官方 `conversation.content` 工厂嵌入 | 自研聊天 / iframe / 打开独立会话 | 官方已提供可复用工厂与 SessionProvider（ui-subagent 先例），复用后消息流、输入框、工具卡片、审批全部由 DSH 负责 |
| AD-2 | Java 后端不碰模型 | 后端直连模型 / 后端调 DSH API | 需求要求"用 DSH 的会话"；避免重做 agent loop |
| AD-3 | 产出登记以数据库为准 | 页面扫描文件目录 | 避免"文件在但未登记"的假完成 |
| AD-4 | 需求=一个 workspace | 项目=一个 workspace | 会话按需求隔离；满足修正 M1 |
| AD-5 | 节点推进后端事务判定 | 前端判定 | 并发安全，可审计 |
| AD-6 | MyBatis 手写 SQL | JPA | 需求明确要求 MyBatis |

## 12. 待确认

| # | 项 | 影响 |
|---|---|---|
| A1 | ~~侧栏入口落位~~ 已确认：面板条目 | — |
| A2 | ~~会话呈现方式~~ 已确认：节点页内嵌官方会话组件（§2.1） | — |
| A3 | 是否需要 Docker 交付 | 影响部署文档 |
