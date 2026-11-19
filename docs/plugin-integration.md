---
document: plugin-integration
status: draft
updated: "2026-11-19"
target: "DeepSeek Harness Desktop (bundled dsh 0.2.2-rc.5)"
---

> **版本 v2（2026-11-19 修订）**：本插件改为**整页插件方案**——业务界面以 React 组件注册进 `main` 面板，节点页内直接嵌入 DSH 原生会话，不再使用 iframe 与 postMessage。来源：源码核实 `ui-subagent` 的 `conversation.content` 工厂嵌入先例。

# Web 工作台 × DeepSeek Harness 插件集成设计

本文回答三个问题：**怎么以插件形式接入 DSH**、**怎么让大模型按节点要求产出信息**、**怎么让产出的信息显示在业务页面上**。所有结论来自对官方 DSH 包源码与参考项目 `FF-FDE-Delivery-Workbench` 的实读，未验证处单独标注。

---

## 1. 接入总览

```text
┌──────────────────────── DSH（官方安装，不改源码）────────────────────────┐
│  插件包 fde-workbench-bundle                                            │
│  ├── Host 半 [TypeScript / Cordis]                                      │
│  │    ├── 4 个模型工具（ctx.tools）                                      │
│  │    ├── 内置指导技能（ctx.skills.register）                            │
│  │    ├── 节点上下文 system prompt 段（ctx.systemPrompt.section）         │
│  │    └── 2 条 Host 路由（ctx.webServer.register）                       │
│  └── Client 半 [React]                                                   │
│       ├── sidebar.panellist      → 「Web 工作台」入口（面板列表第一行）      │
│       ├── main (keyed)           → 工作台整页（业务 React 组件）            │
│       └─ 会话嵌入              → conversation.content 工厂 + SessionProvider│
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │ HTTP（JWT / 内部密钥）
                              Java 业务后端
```

**为什么分两半**：界面与 DSH 交互（会话嵌入、工具结果回显）必须在 DSH 进程内完成，因此 Client 半是插件页面；业务数据、权限与文件仍由 Java 负责，所以 Host/Client 半只做适配，不复制业务规则。

---

## 2. 参考项目是怎么做的（结论）

| 问题 | 参考项目实现（已读源码） | 证据文件 |
|---|---|---|
| 如何接入对话 | **不嵌入**：业务页面用 React 直接注册成 DSH 会话的一个中央页签 | `packages/client/ui-fde-project/src/client/index.ts`（`ctx.slots.inject('conversation.view')`） |
| 如何定义阶段信息 | Host 插件把阶段状态存 `storageDomain`，用 `expectedRevision` 做乐观锁，人工门禁路由推进 | `packages/fde/stage/src/index.ts`、`state.ts`、`http.ts` |
| 如何让模型输出信息 | 三层组合：① `ctx.skills.register` 注册方法技能；② `ctx.systemPrompt.section` 注入极短阶段提示（按 workspace 定位当前阶段）；③ 注册专用工具（如 `architecture_get/architecture_set`、`project_runtime_report`） | `packages/fde/stage/src/index.ts`（`systemPrompt.section`）、`v2-skills.ts` |
| 如何让输出显示在界面 | 模型按技能要求写固定路径文件 → Host 路由重新扫描并按项目目录校验 → 前端 controller 拉取 → Slot 渲染 → 下载 | `packages/fde/stage/src/http.ts`、`ui-fde-project/src/client/stage-controller.ts` |
| 会话如何与项目绑定 | 一个项目（workspace）下多条 Session；`uiWorkspace.connectWorkspace()` 建/复用空白会话，`uiWorkspace.openSession()` 打开已有，`latestProjectSessionId()` 选最近活跃 | `packages/client/ui-workspace/src/client/navigation.ts`、`ui-fde-project/src/client/project-data.ts` |

**本项目的异同**：接入方式与参考项目同属"DSH 插件页面"（同一个 React 树，没有 iframe），因此会话嵌入可以直接用官方工厂；不同的是参考项目把会话整体作为**页面中央视图**，本项目把会话嵌进**节点页的一个视图**里，并通过 `primary_session_id` 把会话与节点绑定。三层模型能力（技能 / prompt 段 / 工具）与参考项目保持同一模式。

---

## 3. 插件包结构

```text
dsh-workbench-plugin/                     # 独立仓库或本仓库 plugin/ 目录
├── package.json                          # 含 dsh.bundle.patch 与 dsh.client
├── cordis.patch.yml                      # 插入 Host 行与 Client 行
├── lib/                                  # 构建产物（Host entry + client bundle）
│   ├── index.js
│   └── client.js
├── src/
│   ├── host/
│   │   ├── index.ts                      # apply：注册工具 / 技能 / prompt 段 / 路由
│   │   ├── tools/                        # node_context_get 等 4 个工具
│   │   ├── skill.ts                      # 内置指导技能正文
│   │   ├── prompt.ts                     # 节点上下文 prompt 段
│   │   └── routes.ts                     # /api/fde-workbench/*
│   └── client/
│       ├── index.ts                      # 注册入口与 overlay
│       ├── WorkbenchLauncher.tsx         # 侧栏入口按钮
│       ├── NodeSessionView.tsx           # 官方 conversation.content 工厂嵌入
│       └── SessionController.ts          # 会话解析 / 新建 / retain
├── locale/{en,zh}.json                   # meta.title / meta.description
├── icon.svg
└── skills/workbench-node-delivery/SKILL.md
```

`package.json` 关键字段：

```json
{
  "name": "@local/dsh-workbench-bundle",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "exports": {
    ".": "./lib/index.js",
    "./client": "./lib/client.js",
    "./locale/*.json": "./locale/*.json",
    "./icon": "./icon.svg"
  },
  "dsh": {
    "bundle": { "patch": "./cordis.patch.yml" },
    "client": {
      "platform": "web",
      "inject": [
        "@deepseek-ai/dsh-client-locale",
        "@deepseek-ai/dsh-client-ui-layout",
        "@deepseek-ai/dsh-client-ui-sidebar",
        "@deepseek-ai/dsh-client-ui-workspace"
      ]
    }
  },
  "files": ["lib", "locale", "icon.svg", "skills", "cordis.patch.yml"]
}
```

> 安装方式：用 `plugin_manager` 的 `install_bundle` 指向插件目录完成安装与启用；替换已安装的包后需要重启 DSH 才能加载新一代 JS 模块。

---

## 4. Client 半：入口与嵌入

### 4.1 侧栏入口（已确认：面板条目）

**官方侧栏结构（源码核实：`dsh-v0.2.2-rc.5` 的 `packages/client/ui-sidebar/src/client/SidebarRoot.tsx`）**：

```text
┌ 侧栏 ───────────────────────────┐
│ 品牌行 + 新建会话按钮              │
│ 全局面板列表 sidebar.panels ⭐     │ ← 「Web 工作台」在这里（order -100）
│   Web 工作台 / 插件 / 定时任务 …   │
│ 会话浏览区 sidebar.workspaces     │
│ 底部操作区 sidebar.footer.action  │
│ 设置 sidebar.settings             │
└─────────────────────────────────┘
```

**事实**：该面板列表目前在 `dsh-v0.2.2-rc.5` 中只有两个注册项——插件管理器（`FIXED_PANEL_ID='plugins'`，`order: 0`）与定时任务（`order: 10`）。注册一个 `order: -100` 的面板条目即可排在**列表第一行、会话列表之上、所有官方条目之前**，满足"第一个选项的上面"的意图。

**实现方式（已确认）**：注册 `sidebar.panellist`（入口行）+ `main` keyed 面板（`key` 相同，承载工作台整页 React 组件）。二者 `id` 必须是同一个 `MainPanelId`。官方插件管理器与定时任务使用同一机制。

- 点击入口 → `ctx.layout.selectPanel(PANEL_ID)` → 中央主区域整块显示工作台，无需覆盖层。
- 不占用任何官方 `single` 位置（`sidebar.brand.*`、`sidebar.workspaces` 等），不与他人抢声明。
- 面板内渲染的是**空位**（中央主区域，不含侧栏），因此工作台页自带「返回项目 / 返回项目中心」导航；需要回到 DSH 会话时用侧栏的会话列表或 `ctx.layout.selectPanel(null)`。
- 组件需要：`id`（`MainPanelId`）、`order`、`label`、图标组件；尺寸与样式参考 `ui-schedule` 的 `TaskManagerIcon`。

### 4.1.1 节点页里的「会话」如何嵌进来（整页方案核心）

节点页顶部的两个视图页签 `会话 / 节点信息`，**都在同一个工作台页面内**，不需要跳转：

```text
RequirementPage
├─ 视图页签：会话 | 节点信息        （仅当前节点且我为创建者时显示「会话」）
├─ 会话视图 → NodeSessionView
│    ├─ SessionController.resolve(requirementId, nodeInstanceId)   // §5.6
│    ├─ ctx.sessions.retain(sessionId)                             // 官方 Session 引用
│    └─ <SessionProvider session={reference}>
│         {renderFactorySlot('conversation.content',
│             { variant: 'embedded', phase, hero },
│             { slots: { views: NodeChatViews } })}
│         // NodeChatViews = props => props.renderSlot('conversation.session', { view: 'chat' })
└─ 节点信息视图 → NodeInfoPanel（工作台自己的组件）
```

**依据（`dsh-v0.2.2-rc.5` 源码）**：

| 事实 | 位置 |
|---|---|
| `conversation.content` 是官方注册的可复用工厂，`variant` 支持 `'main' \| 'embedded'` | `ui-conversation/src/client/apply.ts`、`contract/slots.ts: ConversationContentInputProps` |
| 已有人用它在**非主面板位置**渲染完整会话：`ui-subagent` 把子会话渲染进右侧栏 | `ui-subagent/src/client/sidebar-chat/index.tsx`（`ConversationSlotPanel`） |
| `sessions.retain(target: SessionTarget)` 接受任意 `SessionId`，不限于子会话 | `api/session-controller/src/client/sessions/service.ts`、`contract/sessions.ts` |
| `<SessionProvider>` 是 `ui-slots`/`ui-renderer` 提供的标准会话作用域席位 | `ui-slots/src/index.ts`、`ui-renderer/src/client/scoped-slots.tsx` |
| `embedded` 变体不渲染全屏 Hero（`hero && variant !== 'embedded'`），尺寸由宿主容器决定 | `ui-conversation/src/client/skeleton/ConversationContent.tsx` |

**实测结论（源码 + 运行期共同确认，代码以此为准）**：

1. **不要自建工厂承载会话内容**。曾尝试的写法是把局部槽位名当作工厂名调用 `renderFactorySlot('workbench.node.chat.conversation', …)`；`FactoryOutlet` 里 `host.factoryOf()` 查不到定义后**直接返回 null**（`ui-renderer/src/client/scoped-slots.tsx`），结果是"高度正常、内容空白、无报错"。这类静默失败必须靠 DOM 断言发现。
2. **`SessionProvider` 不在 `PropsRuntime` 上，而在 `PropsRenderSlots` 里**：它由 `standardKit()` 按 `entry.children` 是否含非 root 槽位发放。因此承载它的注册项**必须真的声明一个会话子槽位**，不能用单层槽位简化。
3. 正确结构即官方 `registerSidebarChat()` 的两级槽位：

```text
main 面板 children 声明 '{ workbench.node.chat: { kind: 'single', scope: 'session' } }'
  └─ slots.inject('workbench.node.chat', () => register(shell))
       PropsRuntime & PropsRenderSlots<'workbench.node.chat.conversation'>
       组件内：<SessionProvider session={reference}>
                 renderSlot('workbench.node.chat.conversation', {})
               </SessionProvider>
  └─ slots.inject('workbench.node.chat.conversation', () => register(panel))
       组件内：renderFactorySlot('conversation.content',
                 { variant: 'embedded', phase, hero }, { slots: { views: FixedChatView } })
```

4. **槽位注册必须用 `ctx.slots.inject(key, cb)`**，不能裸 `register`：插件里槽位注册若先于父条目的 `children` 声明执行，会抛 `slot "…" is not declared` 导致整个插件激活失败。`inject` 会在声明就绪时才执行回调。
5. 组件把实际的 `phase`/`hero` 写到容器 `data-*` 属性上，便于装好后在 DevTools 与 DOM 断言里核对。

**实现要点**：

1. `NodeSessionView` 用一个固定宽高的容器（`height: 100%`）包住工厂输出；`embeddedBody` 样式会让内容填满容器。
2. `phase` 与主面板同规则计算（blank → hero，active → active，加载中 → settling），避免闪屏：
   `active = conversation.activeTargets.size > 0 || (!session.blank && !session.awaitingFirstTurn) || session.running`。
3. 会话切换器与「新建会话」放在视图顶部，由工作台自己实现（读 `wb_agent_session` 列表），新建流程见 §5.6。
4. 会话失败（retain 抛错、会话已被删除）时，只在该视图区内显示错误与「新建会话」，`节点信息` 视图不受影响。
5. 不使用 `ctx.layout.selectPanel(null)`，用户始终留在工作台面板内。

### 4.2 工作台整页（main 面板）

- 渲染根组件 `WorkbenchMain`，页面栈：`项目中心 → 项目（四 Tab）→ 需求 → 节点`，用应用内快照 store 维护，不写 DSH 存储。
- 主题：直接继承 DSH 的 `--dsw-alias-*` 令牌（同一 React 树，无需同步）。
- 导航：面板不含 DSH 侧栏，页面顶部提供「返回项目 / 返回项目中心」。
- 数据：全部经 `api/` 调用 Java 后端（fetch + JWT）；JWT 由工作台自己的登录页签发与保存（`localStorage`），与 DSH 自身账号无关。

---

## 5. Host 半：让模型按节点要求产出

### 5.1 工作目录与路径约定

| 概念 | 路径 |
|---|---|
| 需求 workspace | `<storageRoot>/ws/<requirementId>`（DSH session cwd） |
| 模型输出目录 | `.workbench/<nodeInstanceId>/<itemKey>.<ext>` |
| 节点输入 | `inputs/<nodeSeq>/<fileId>_<原名>` |
| 上一节点输出 | `outputs/<nodeSeq>/<itemKey>.<ext>` |

- 因为**一个需求一个 workspace**，模型天然只能看到本需求的资料；跨需求隔离由 DSH workspace 提供，无需额外沙箱逻辑。
- 因为输出写在 workspace 内，Harness 的 `read`/`glob`/`grep` 等原生工具可以直接读到，页面也能同时看到。

### 5.2 工具设计

四个工具都注册在 Host 插件上下文（`ctx.tools`），模型在任意 DSH 会话中可用；工具内部按"当前会话 cwd → workspace → requirementId"解析上下文，解析不到时返回明确错误而不是静默成功。

#### `node_context_get`

| 项 | 内容 |
|---|---|
| 用途 | 获取当前节点完整快照，模型开始工作前必须调用 |
| 参数 | `{ requirementId?: string, nodeInstanceId?: string }`（缺省取会话所在 workspace 的当前节点） |
| 返回 | 节点名称与说明；输入项列表（键、名称、说明、必填、文件相对路径）；输出项列表（键、名称、说明、文件类型、必填、是否已完成、已有文件路径）；上一节点输出文件列表；生效技能名列表；输出目录 |
| 失败 | workspace 未绑定需求 / 节点不存在 / 非本人需求时返回可读错误 |

#### `node_reference_read`

| 项 | 内容 |
|---|---|
| 用途 | 读取节点输入或上一节点输出的真实文件内容 |
| 参数 | `{ kind: 'input'|'previousOutput', itemKey: string }` |
| 返回 | 文档正文或二进制文件的元信息（类型、大小、sha256）+ 提示"需用 read 工具读取绝对路径" |
| 约束 | 单次返回按 token 上限截断，并在结果中显式标注"已截断，剩余 X 字节" |

#### `node_output_write`

| 项 | 内容 |
|---|---|
| 用途 | 把生成的输出写入固定输出目录，**唯一允许的落盘方式** |
| 参数 | `{ itemKey: string, content: string }` 或 `{ itemKey: string, sourcePath: string }` |
| 约束 | `itemKey` 必须属于本节点输出项；只允许写 `.workbench/<nodeInstanceId>/` 下；扩展名按输出项 `file_kind` 白名单校验；单文件大小上限（默认 10 MiB，可在 `Config` 调整） |
| 返回 | `{ relativePath, sha256, bytes }` |
| 失败 | 键不存在、扩展名不允许、超限、越界路径均返回明确错误 |

#### `node_output_commit`

| 项 | 内容 |
|---|---|
| 用途 | 向业务后端登记该输出项，登记后才能被页面展示与下载 |
| 参数 | `{ itemKey: string, relativePath: string, sha256: string }` |
| 内部流程 | 校验 sha256 与实际文件一致 → POST `{javaBaseUrl}/internal/nodes/{nodeInstanceId}/outputs/{itemKey}/commit`（携带 `X-Internal-Token`）→ 回传后端结果 |
| 返回 | `{ committed: true, versionNo, remainingRequiredOutputs }`；全部必填项完成时额外返回 `{ advanced: true, nextNodeName }` |
| 失败 | 摘要不一致、后端 4xx/5xx 时返回后端原始错误信息，不重试 |

> **每个工具的描述都写成"模型视角"的行为说明**，不写实现细节；参数规则写在参数描述里。

### 5.3 内置指导技能

- 名称：`workbench-node-delivery`；随插件以 `ctx.skills.register` 注册，永远可用。
- 内容要点（完整正文见插件 `skills/workbench-node-delivery/SKILL.md`）：
  1. 先 `node_context_get` 获取输出项清单，不要凭记忆猜测输出项。
  2. 逐项生成内容；信息不足时先追问，不要编造。
  3. 每个输出项**必须**用 `node_output_write` 写盘，不要用 `edit`/`write` 直接写其他路径。
  4. 写盘成功后立刻 `node_output_commit`；任一必填项失败要说明原因并继续修复。
  5. 明确告诉用户：已完成几项、还剩几项、在页面的哪个区块可以下载。
  6. 跨节点引用上一节点输出时用 `node_reference_read` 读取，不要假设路径。

### 5.4 节点自定义技能

- 节点可配多个技能（修正 M3）。Host 半在构造 prompt 段时把这批技能**名称与版本**列出来，模型按名称自行加载。
- 版本隔离：`skills_snapshot` 记录了版本号，但 DSH 技能注册是全局的；因此**同一技能同一时刻只发布一个版本**（最新版本）。历史版本的严格加载列为待确认项（见第 8 节 V2）。

### 5.5 节点上下文 prompt 段

`ctx.systemPrompt.section()` 只放**一行**，并且只在会话 cwd 属于某个需求 workspace 时出现：

```text
当前处于需求《{requirementTitle}》的节点 {seq}/{total}「{nodeName}」。
生效技能：{skill1}、{skill2}。输出必须用 node_output_write 写入后调用 node_output_commit；
输出目录为 .workbench/{nodeInstanceId}/。开始前先调用 node_context_get。
```

- 控制 token：技能正文不进 prompt，由模型按需加载；节点输入输出明细也不进 prompt，由 `node_context_get` 提供。
- 与参考项目一致：prompt 段是"短指令 + 按需技能"，不是把全部规则塞进系统提示。

---

## 6. 会话路由与新建（Client 控制器 + Host 路由）

工作台页面与 DSH 会话服务在**同一个 React 树、同一个 Cordis 上下文**里（不再有 postMessage 桥），因此大部分会话操作在 Client 半直接完成：

| 操作 | 执行位置 | 说明 |
|---|---|---|
| 解析默认会话 | Client `SessionController` + Java | 先查 Java 的节点绑定与会话列表 |
| 新建会话 | Client `uiWorkspace.connectWorkspace` + Java 登记 | 见下方流程 |
| 嵌入渲染 | Client `ctx.sessions.retain` + `conversation.content` | 见 §4.1.1 |

Host 路由只在**需要跨进程**时使用：

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/fde-workbench/session/resolve` | 供 Java 侧（或未来外部入口）按需求解析默认会话 |
| POST | `/api/fde-workbench/session/open` | 供外部入口直接打开某会话 |

> Host 路由只接受同源请求（复用 `createWebRequestTrust`）；若需要 Java 直接调用，必须额外校验 `X-Internal-Token`。

**新建会话流程（修正 M4，Client 侧完成）**：

```text
节点页点「新建会话」
→ SessionController.create(requirementId, nodeInstanceId)
   1) ctx.uiWorkspace.connectWorkspace(workspaceId) 得到 sessionId
   2) POST /internal/sessions { dshSessionId, nodeId, title }        // 携带 X-Internal-Token
      → Java 事务：写 wb_agent_session + wb_session_runtime_info
                  + 若 node.primary_session_id 为空则设为新会话 + wb_node_session_link
   3) 用 ctx.sessions.retain(sessionId) 得到引用，视图切换到新会话
```

节点推进后，下一节点的 `primary_session_id` **沿用需求默认会话**（不复制旧节点绑定，避免节点之间相互"抢"默认会话）；只有在该节点主动新建会话时才绑定到该节点。

---

## 7. 产出如何显示在页面上（端到端链路）

```text
用户对话：“帮我生成需求条目清单”
   │
   ▼
模型调用 node_context_get ──→ 拿到输出项 requirement-items / acceptance-criteria
   │
   ▼
模型生成内容 → node_output_write
   │  写入 <ws>/.workbench/<nodeInstanceId>/requirement-items.md，返回 sha256
   ▼
模型调用 node_output_commit
   │  Host 校验 sha256 → POST /internal/nodes/{nid}/outputs/{key}/commit
   ▼
Java 后端事务：
  1) 校验文件存在且 sha256 一致
  2) 写 wb_file（ref_type=node_output）与 wb_requirement_node_output（版本 +1）
  3) 重新计算必填项是否齐全；齐全则推进节点（node 置 done、下一节点置 current、requirement.current_seq+1）
  4) 返回 { committed, versionNo, advanced, nextNodeName }
   ▼
工作台（同一 React 树）：监听到会话内的工具结果（或节点信息视图重新获焦时）
   │  → 重新拉取 GET /api/requirements/{id}/nodes/{nodeId}
   ▼
页面「输出项」区块出现文件与「下载」按钮；全部完成时自动进入下一节点
```

关键点：

- **页面不扫描文件系统**：是否"完成"的唯一依据是数据库登记，避免文件存在但未登记造成的假完成。
- **重启不丢**：输出文件在 workspace 内、登记在 MySQL，页面刷新与跨会话都可见。
- **失败可见**：工具失败时模型会得到明确错误并继续修复，页面显示"待补充"而不是假装完成。

---

## 8. 兼容性与风险

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | 官方 Slot 或服务方法改名 | 入口或嵌入失效 | 只依赖稳定服务名；插件加载失败时 DSH 其余功能不受影响；升级后按本表快速核对 |
| R2 | 嵌入会话与工作台自有快捷键冲突（同一 React 树） | 输入体验 | 工作台只在会话视图外注册快捷键；阶段 0/6 实测 `Esc Esc` 停止等原生快捷键仍生效 |
| R6 | 业务前端依赖 DSH Client 契约，DSH 升级可能改签名 | 需要回归 | 只依赖本节 §4.1.1 列出的 5 个稳定面；升级后跑插件回归清单（阶段 7） |
| R7 | `conversation.content` 工厂在未来版本可能调整 `variant` 语义 | 会话视图渲染异常 | 阶段 0 写一个最小嵌入冒烟用例；异常时降级为"打开 DSH 会话"入口 |
| R3 | 多技能同时生效导致 prompt 膨胀 | token 成本 | prompt 段只列技能名；正文按需加载（实测参考项目做法） |
| R4 | DSH 会话日志被清理 | 会话恢复失败 | 后端对账后标记失效并提示新建，不伪造 |
| R5 | 模型不调用 commit | 页面看不到产出 | 技能 + 工具描述 + `node_context_get` 返回明确的"未提交项"；工具结果里反复提示剩余项 |
| V1 | 入口在侧栏的精确视觉位置 | 与需求"第一个选项上方"可能差一格 | **待实机验证**，验证后固定 `order` |
| V2 | 技能历史版本不能并行加载 | 同一技能升级后旧节点用新版本 | 列为 P1；方案是给版本化技能加后缀发布 |
| V3 | `install_bundle` 需要 Full access | 安装步骤有限制 | 文档写明安装需要完整权限 |

### 已确认的 DSH 机制（来自官方包）

- 客户端通过 `ctx.slots.inject(ownerKey, () => ctx.slots.register({…}, Component))` 注册 UI，注册随插件生命周期回收。
- Host 工具、技能、prompt 段、HTTP 路由分别来自 `ctx.tools`、`ctx.skills`、`ctx.systemPrompt`、`ctx.webServer`，均为官方公开扩展点。
- workspace 与 session 通过 `workspaceRegistry` / `uiWorkspace` 访问，无需读取 DSH 私有状态文件。

### 尚未验证

1. 嵌入的 `conversation.content` 在 `main` 面板中的尺寸与滚动行为（阶段 0 写冒烟用例实测）。
2. `sessions.retain` 对"主会话"与"子会话"是否有权限差异（源码未见限制，需实测）。
3. 嵌入会话内的审批弹窗（`ui-approval` composer takeover）在非主面板位置的表现。
3. 后台调用 `node_output_commit` 时 Java 侧长事务对 DSH 响应时间的实际影响。

以上三项列入 [development-plan.md](development-plan.md) 阶段 0 与阶段 6 的验证任务。
