# Web 工作台插件包（`@local/dsh-workbench-bundle`）

FDE 交付工作台以 **DSH 插件包（bundle）** 的形式接入 DeepSeek Harness：

- **侧栏入口**：全局面板列表第一行「Web 工作台」（`sidebar.panellist`，`order: -100`）。
- **工作台整页**：同一个面板 id 的 `main` keyed 面板（无 iframe、无覆盖层）。
- **会话嵌入**：面板内嵌一个真实 DSH 会话（`conversation.content` 工厂，`variant: 'embedded'`），
  输入框、消息流、工具卡片、`Esc Esc` 停止等原生行为全部由 DSH 自己渲染。

本包当前是 **T0-3 阶段**（骨架 + 会话嵌入冒烟）：Host 半为空的 `apply`，
不调用任何业务接口（Java 后端尚未存在）。

> **验证状态（务必先读）**
> - ✅ 已在本机验证：构建产物、Host 入口可加载、Client bundle 的模块加载器契约、
>   插件在最小测试台里注册出的槽位与文案。
> - ❌ **未执行**：安装进任何 DSH profile、在浏览器里点击侧栏入口、肉眼确认会话是否渲染。
>   原因是项目改为**自带可携带 dsh**（见下文 §4），且本机正在运行的是用户的 Desktop 版，
>   本任务被要求不要往 `~/.dsh/profiles/desktop` 安装、也不要重启用户的 Desktop。

---

## 1. 目录结构

```text
fde-workbench-bundle/
├── package.json            # dsh.bundle.patch + dsh.client（platform: "web"）+ exports + files
├── cordis.patch.yml        # 插入 Host 行（fde-workbench）与 Client 行（fde-workbench-client）
├── tsconfig.host.json      # src/host        → lib/types/host
├── tsconfig.client.json    # src/client      → lib/types/client
├── tsdown.config.ts        # 调用 DSH 官方 clientBundle 预设
├── scripts/build.sh        # 一键构建（见 §2）
├── src/
│   ├── host/index.ts       # Host 半：export const name / export function apply（空实现）
│   └── client/
│       ├── index.ts        # 注册 sidebar.panellist + main 面板 + 嵌入会话工厂
│       ├── locales.ts      # 运行期文案字典（zh / en，key 集合的权威来源）
│       ├── main/WorkbenchMain.tsx      # 工作台外壳 +「返回」
│       ├── main/WorkbenchPanelIcon.tsx # 侧栏图标
│       └── session/NodeSessionView.tsx # 会话嵌入冒烟（见 §3）
├── locale/{zh,en}.json     # 插件管理器读的 meta.title / meta.description / panel.label
├── icon.svg                # 插件图标（876 字节，原创，currentColor）
├── skills/README.md        # 占位：内置技能属 T5-2
├── lib/                    # 构建产物（见 §2）
│   ├── index.js            # Host 入口（ESM）
│   └── client.js           # 浏览器 bundle（CJS 工厂）
└── README.md
```

## 2. 构建（已实测跑通）

采用 **DSH 官方工具链**，与被 `install_bundle` 安装的 `dsh-client-ui-*` 包走同一条产线：
`tsc` 编出 `lib/types`，再由 `tsdown` + `packages/client/tsdown.client.ts` 的 `clientBundle` 预设
打包浏览器产物。

### 2.1 前置条件

1. **一份已构建依赖包的 DSH 源码 checkout**。本机验证用 `/tmp/dsh-plugin-dev`
   （`/tmp/dsh-src` 的副本，`/tmp/dsh-src` 本身保持未改动）。准备步骤：

   ```bash
   cp -R /tmp/dsh-src /tmp/dsh-plugin-dev
   rm -rf /tmp/dsh-plugin-dev/.git          # 让它成为一个独立 checkout，避免误改上游
   cd /tmp/dsh-plugin-dev
   corepack pnpm install                    # 拉取 Node 侧依赖
   # 官方方式编译 Host 半（含 Typert 产物生成 + 全仓 tsdown host 构建，约数分钟）
   corepack pnpm exec tsx scripts/compile-referenced-projects.ts host-libraries
   # 编译 Client 半的 tsc 产物（客户端插件需要的 lib/types；全仓 250 个项目）
   corepack pnpm exec tsc -b tsconfig.client.json
   ```

   > 这一步只为了生成 **类型声明与依赖包的 lib 产物**；插件自己的 tsc 只用到
   > `packages/*/src` 的类型（见 `tsconfig.client.json` 的 `extends` + `paths`）。

2. **把插件包挂进该 checkout**（`tsdown --config` 以仓库根为 cwd，`clientBundle` 预设依赖仓库根目录）：

   ```bash
   mkdir -p /tmp/dsh-plugin-dev/third-party
   ln -sfn /Users/frankchan/Desktop/fde-platform-local/fde-workbench-bundle \
           /tmp/dsh-plugin-dev/third-party/dsh-workbench-bundle
   ```

   并在 `/tmp/dsh-plugin-dev/tsdown.config.ts` 第 23 行的 workspace glob 里追加
   `'third-party/*/package.json'`（只改这份临时 checkout，不改 `packages/` 下任何 DSH 包）。

### 2.2 构建命令

```bash
cd /Users/frankchan/Desktop/fde-platform-local/fde-workbench-bundle
DSH_SRC=/tmp/dsh-plugin-dev bash scripts/build.sh
```

`scripts/build.sh` 的三步（等价于官方 `build:lib:client` 对单个包的做法）：

```bash
# 1) Host 半
$DSH_SRC/node_modules/typescript/bin/tsc -p tsconfig.host.json      # → lib/types/host/index.js
# 2) Client 半的 tsc 产物（只对本包源码报错；依赖包诊断忽略，见脚本注释）
$DSH_SRC/node_modules/typescript/bin/tsc -p tsconfig.client.json    # → lib/types/client/**.js
# 3) 官方 tsdown 预设（cwd = DSH 源码 checkout）
cd $DSH_SRC && DSH_SRC=$DSH_SRC corepack pnpm exec tsdown --config-loader native \
  --config <插件包>/tsdown.config.ts
```

`tsdown.config.ts` 只有一行实质内容：

```ts
const { clientBundle } = await import(`${DSH_SRC}/packages/client/tsdown.client.ts`)
export default clientBundle(PACKAGE_DIR, ['lib/types/host/index.js'])
```

### 2.3 实测产物

```text
$ ls -la lib
-rw-r--r--  client.js        11405 B   ← 浏览器 bundle（CJS 工厂）
-rw-r--r--  client.js.map    34975 B
-rw-r--r--  index.js           929 B   ← Host 入口（ESM：export name / apply）
drwxr-xr-x  types/                     ← tsc 中间产物（host/ 与 client/）
```

产物契约（与官方一致，已逐项核对）：

| 项 | 实测结果 |
|---|---|
| `lib/client.js` 形态 | CJS 工厂：`window.__ModuleLoader__.load({id:"@local/dsh-workbench-bundle",factory:require=>{…return module.exports}})` |
| 外部依赖 | 只有 `react`、`react/jsx-runtime`（均在平台基线模块表 `PLATFORM_MODULES` 里） |
| 其余依赖 | 全部内联（本包没有第三方运行期依赖） |
| `lib/index.js` | `node --input-type=module -e "import('./lib/index.js')"` → `['apply','name']`，`name = '@local/dsh-workbench-bundle'` |

### 2.4 出现过的坑（供后续复现）

- `tsconfig.client.json` 用 `extends: <DSH_SRC>/tsconfig.base.client.json` 借 DSH 的 `paths`
  （插件包目录不在 DSH workspace 里，`node_modules` 中也没有 `@deepseek-ai/*`）。
  DSH 源码因此进入本包的 program，会产生一批**依赖包自身的**诊断（`TS6059` 等）；
  `build.sh` 只把 `^src/.*error TS`（且非 `TS6059`）当错误，其余忽略并打印计数。
- `clientBundle` 的 Host 入口必须与 tsc 产物路径一致：本包 Host 代码在 `src/host/`，
  所以入口是 `lib/types/host/index.js`（官方包是 `src/index.ts` → `lib/types/index.js`）。

## 3. 会话嵌入是怎么做的（只用官方公开扩展点）

```text
main 面板（WorkbenchMain）children 声明 'workbench.node.chat'
└─ NodeSessionView
   ├─ 解析 sessionId：会话列表里最近活跃的非子会话（Host 按最近活跃排序）
   ├─ ctx.sessions.retain(sessionId, { source: 'workbenchEmbed' })   ← 官方 Session 引用
   ├─ renderSlot('workbench.node.chat', { reference })               ← 外壳槽位
   │   └─ WorkbenchChatShell：<SessionProvider session={reference}>
   │        renderSlot('workbench.node.chat.conversation', {})       ← 内容座席
   │          └─ WorkbenchConversationPanel：
   │               renderFactorySlot('conversation.content',
   │                 { variant: 'embedded', phase, hero },
   │                 { slots: { views: FixedChatView } })
   └─ 失败态：会话拿不到 / retain 抛错 → 本区域内显示错误 + 「新建会话」+「重试」，不白屏
```

关键结论（源码实读 + 类型检查 + 运行期实测）：

1. **不要自建工厂承载会话内容**。早期写法把局部槽位名 `workbench.node.chat.conversation`
   当作工厂名传给 `renderFactorySlot`；`FactoryOutlet` 中 `host.factoryOf()` 查不到定义后
   **直接返回 null**（`ui-renderer/src/client/scoped-slots.tsx`），表现为"容器高度正常、
   内容空白、控制台无报错"。
2. **`SessionProvider` 不在 `PropsRuntime` 上，而在 `PropsRenderSlots` 中**：`standardKit()`
   按 `entry.children` 是否含非 root 槽位发放。所以外壳注册项必须真实声明一个会话子槽位，
   这正是官方 `SidebarChatTabProps = PropsRuntime<…> & PropsRenderSlots<…>` 的形状。
3. **槽位注册要用 `ctx.slots.inject(key, cb)`**，不能裸 `register`：注册若先于父条目
   `children` 声明执行，会抛 `slot "…" is not declared` 让整个插件激活失败。
4. **尺寸/滚动**：外层容器 `display:flex; flex-direction:column; height:100%; min-height:0`，
   工厂输出自己撑满；`embedded` 变体不渲染全屏 Hero（`hero && variant !== 'embedded'`）。
5. `phase` / `hero` / `openState` 的实际取值写在容器 `data-embed-*` 属性上，可在 DevTools 核对。

## 4. 可携带 dsh（Web 形态，调研结论 —— 仅记录，未落地部署）

目标：项目自带一个**可搬运的 Web 形态 dsh**（`dsh web`，浏览器访问），
不依赖用户本机的 DeepSeek Harness Desktop、不读写 `~/.dsh`。
以下结论来自 `/tmp/dsh-src`（tag `dsh-v0.2.2-rc.5`）源码实读，逐条标注「实测 / 源码实读 / 未验证」。

### 4.1 启动方式与默认端口

| 项 | 结论 | 依据 |
|---|---|---|
| 命令 | `dsh web` 等价于 `dsh --profile web`（启动器自己的 help 里写明的别名） | `apps/cli/src/args.ts:92` 源码实读 |
| 默认端口 | **3080**，由 bundle patch 的 `!!js ctx.webStartup.port ?? 3080` 决定 | `packages/boot/cmdline/src/index.ts:14` 注释与 `packages/bundle/web-app/src/startup.ts` 源码实读 |
| 端口/绑定参数 | `dsh web --host <本地IP> --port <端口> [--no-open] [--public-url …] [--trusted-host …] [--tls-cert/--tls-key]`；`--port 0` 让系统选空闲端口 | `startup.ts` 的 `webCommand()` 源码实读 |
| 进程入口 | `node <checkout>/apps/cli/lib/bin.js web --port 3081 --no-open`（`apps/cli` 的包名是 `@deepseek-ai/dsh`，`bin.dsh = lib/bin.js`） | 源码实读；dev 副本里 `apps/cli/lib/bin.js` 已生成 |
| 隔离 | 启动前设 `DSH_HOME=<repo>/runtime/dsh-home`；共享 agent 配置可用 `DSH_AGENTS_HOME` 单独控制 | `packages/util/home-paths/src/index.ts:135` 的 `resolveDshHome()`：显式配置 > `$DSH_HOME` > `~/.dsh` |

**建议的启动形态（本项目落地时）**：

```bash
export DSH_HOME="$PWD/runtime/dsh-home"          # profile / 会话 / 凭据都落在项目内
export DSH_AGENTS_HOME="$PWD/runtime/agents"     # 不读写 ~/.agents
node vendor/dsh/apps/cli/lib/bin.js web --port 3081 --no-open --host 127.0.0.1
```

### 4.2 本项目专属 profile 如何被 `dsh web` 加载

```text
$DSH_HOME/profiles/web/
├── package.json          # dsh.profile.bundles = ['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app']
│                         # dependencies 里加 "@local/dsh-workbench-bundle": "link:<插件目录绝对路径>"
├── cordis.patch.yml      # 本项目自己的覆盖层（模型 provider、端口等），在 bundle 层之后应用
└── node_modules/         # pnpm 生成的依赖（含本插件的 link）
```

- `PROFILE_TEMPLATES.web = ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app']`，
  `dsh plugin init --profile web` 会用这个模板建 profile（`packages/util/official-bundles/src/bundles.ts:14`）。
- 插件树 = 各 bundle 的 `cordis.patch.yml`（按 `bundles` 顺序）+ profile 自己的 `cordis.patch.yml`
  + 启动器 `--patch` 覆盖层（`packages/boot/app-boot/src/profile.ts` 文件头）。
- 本插件的 `cordis.patch.yml` 插入 Host 行 `fde-workbench` 与 Client 行 `fde-workbench-client`；
  Client 半是否被下发，取决于 **Host 行是否已挂载**：client-modules 扫描已挂载的 Host 行，
  读 `package.json` 的 `dsh.client.platform === 'web'` 与 `exports["./client"]`
  （`packages/client/modules/src/index.ts` 的 `resolveMeta()`）。这就是两行必须成对存在的原因。
- 模块解析是「两锚点」：bundle 名先从 dsh 安装目录解析、再从 profile 目录（含 `node_modules`）解析。
- 安装本插件到该 profile：`node <checkout>/apps/cli/lib/bin.js plugin --profile web add <插件目录>`
  （内部即 `installBundle` → pnpm → 写 profile `package.json` + 选中 bundle）；手动等价做法是
  在 profile `package.json` 里加 `link:` 依赖后执行 `pnpm install`。

### 4.3 首次浏览器访问与授权链接

- `dsh web` 启动后会在终端打印一行 **带一次性 token 的访问地址**
  （`web-app` 的 `printUrl`；`--no-open` 只是不自动拉起浏览器，不影响打印）。
- 该地址类似 `http://127.0.0.1:3080/?token=<进程启动 token>`；浏览器打开它会：
  **校验 token → 种一张签名 cookie（`dsh-auth-<authority>`）→ 303 跳到干净的 `./`**。
  之后同一浏览器用 cookie 访问即可。
- 不带 token / cookie 的任何请求都会得到 401 纯文本：
  `dsh web authentication required; reopen the URL printed by dsh web.`
- 结论：**token 属于进程级（每次启动重新生成），不能写死进文档或脚本**；
  落地时应从启动日志里捕获那一行，或让用户手动点。源码实读依据：
  `packages/client/connection/src/browser-auth.ts` 的 `authenticatedUrl()` / `authorizeIndex()`。
  ⬜ 未在本机实测（没有起过 dsh web）。

### 4.4 `dsh web` 起来需要哪些产物

| 产物 | 是否必需 | 说明 |
|---|---|---|
| 依赖包的 `lib/`（Host 半 JS） | ✅ 必需 | `node_modules` 里 `@deepseek-ai/*` 在源码 checkout 中是 **workspace link**，`lib/` 必须由构建产生（`pnpm exec tsx scripts/compile-referenced-projects.ts host-libraries`） |
| **Web 前端 `dist/`** | ✅ 必需 | `web-app` 用 `import.meta.resolve('@deepseek-ai/dsh-web-frontend/package.json')` 定位 `<apps/web>/dist/index.html`（`packages/bundle/web-app/src/index.ts:123`）。需要跑 `pnpm --filter @deepseek-ai/dsh-web-frontend run build`（`vite build`）。⬜ dev 副本里未跑（`apps/web/dist` 不存在） |
| `apps/cli/lib/bin.js` | ✅ 必需 | 启动器入口；host-libraries 构建已生成 |
| 第三方 `node_modules` | ⚠️ 必需，但可重建 | 源码 checkout 的 `@deepseek-ai/*` 是 symlink 到 `packages/*`，**因此不能只拷单个包**；第三方依赖（react、node-pty 等 native 包）在 `node_modules` 里 |
| **模型 provider / API Key** | ⚠️ 会话能用就需要 | `dsh-base` 默认挂 `llm-deepseek`（key 来自 `$DSH_HOME/.credentials.yaml` 凭据存储或环境变量）与「休眠」的 `llm-pi-ai`（由 `llm-pi-ai:` 设置段提供 provider profile）。没有 key 时界面能起、模型不能应答。可把 provider 配置写进本项目 profile 的 `cordis.patch.yml`（与本机 Desktop profile 的做法相同） |

### 4.5 体积与「拷贝到另一台机器」是否成立

| 项 | 实测 |
|---|---|
| 已构建的源码 checkout 总量 | **2.6 GB**（`/tmp/dsh-plugin-dev`），其中 `node_modules` **2.2 GB**、`packages/` 265 MB、`apps/` 20 MB |
| 能否只拷单目录 | 依赖包是 workspace symlink，**必须整体搬**；目标机需 Node ≥ 24.9（`engines.node`） |
| 能否不拷 `node_modules` | **不能直接省**（第三方 + native 依赖都在里面）；可选：目标机 `corepack pnpm install --prod` 重建（需网络），或 `pnpm deploy --legacy` 把运行时闭包导出成单目录（⬜ 未验证） |
| 官方已有形态 | Electron Desktop 安装包是 `app.asar` + `app.asar.unpacked`（本机 `/Applications/DeepSeek Harness.app`），单应用自包含但不是 Web 形态 |

### 4.6 结论与不确定点

- **可行**：源码 checkout + 官方构建 + 独立 `DSH_HOME` + `dsh web --port …` 是清晰路径；
  profile 通过 `package.json`/`cordis.patch.yml` 加载本插件，完全不碰 `~/.dsh`。
- **落地前必须补的前置步骤（按风险排序）**：
  1. `apps/web` 的 `vite build`（没有 `dist/` 时 `web-app` 起不了页面）；
  2. 首次以独立 `DSH_HOME` 启动 `dsh web --no-open --port 3081`，确认它会在 `runtime/dsh-home/profiles/web` 下初始化 profile；
  3. 用 `plugin --profile web add <本插件>` 安装并确认侧栏首行出现「Web 工作台」；
  4. 决定 provider/Key 的注入方式（profile `cordis.patch.yml` vs. 环境变量）。
- **未验证**：以上 1–4 全部未在本机执行；「2.6 GB → 可搬运单目录」没有验证过的打包方案。

## 5. 安装进「项目自带的 dsh web」（未在本机执行）

> 以下步骤**没有在本机执行**：没有安装进任何 profile，没有启动 dsh，没有碰 `~/.dsh`，
> 也没有重启用户正在使用的 Desktop 版。

1. 按 §4 准备自带 dsh（源码 checkout 或打包目录），并完成 `apps/web` 的前端 `vite build`。
2. 把本插件装进该 profile。`dsh plugin` 本身只是**把剩余参数原样转发给 profile 目录里的 pnpm**
   （`apps/cli/src/args.ts` 的 `plugin` 子命令），所以直接 `add` 一个本地目录即可：

   ```bash
   export DSH_HOME=/Users/frankchan/Desktop/fde-platform-local/runtime/dsh-home
   export DSH_AGENTS_HOME=/Users/frankchan/Desktop/fde-platform-local/runtime/agents
   node <dsh-checkout>/apps/cli/lib/bin.js plugin --profile web add \
     /Users/frankchan/Desktop/fde-platform-local/fde-workbench-bundle
   ```

   该 profile 不存在时会按模板 *初始化 on first use*（help 原文：`initialized on first use`，
   模板见 §4.2）。插件的 `package.json` 声明了 `dsh.bundle.patch`，安装后会被选中为 bundle。

3. 启动（端口与 3080 默认值错开，避免与本机已有实例冲突）：

   ```bash
   node <dsh-checkout>/apps/cli/lib/bin.js web --port 3081 --no-open --host 127.0.0.1
   ```

4. 从启动日志里取那一行带 `?token=…` 的地址，在浏览器打开（首次访问用它换 cookie，见 §4.3）。

**界面验收清单（需人工确认）**：入口在侧栏面板列表第一行 → 点击打开中央面板 →
面板内「节点会话」显示一个真实 DSH 会话（消息可见、可输入、可滚动、`Esc Esc` 可停止）→
点「返回」回到会话视图。

## 6. 已知限制（本阶段）

- 会话来源固定为「最近活跃的非子会话」；T5-3 改为按节点绑定解析。
- 「新建会话」走官方 `ctx.uiWorkspace.startSession()`（DSH 自己的新建流程），不调用业务接口。
- 样式只用了 `--dsw-alias-*` 令牌与内联样式，未引入 CSS Modules（后续任务再统一）。
- `skills/` 仅占位，内置技能属 T5-2。
