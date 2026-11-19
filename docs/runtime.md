# 自带可携带 dsh：构建、启动与运维

本文说明本项目**自带一套 DeepSeek Harness（浏览器形态 `dsh web`）** 的构建与启动方式。
它与目标机上已安装的 Desktop 版 / 全局 dsh **完全隔离**：不读、不写 `~/.dsh`，不碰 `~/.agents`，
使用独立端口（默认 dsh `3080`、Java 后端 `8081`）。

设计原则见 [architecture.md §13](architecture.md#13-自带可携带的-deepseek-harness部署原则)；
插件本身的设计见 [plugin-integration.md](plugin-integration.md)；插件构建的实测细节见
[fde-workbench-bundle/README.md](../fde-workbench-bundle/README.md)。

```text
一条命令准备环境  →  ./scripts/setup-runtime.sh
一条命令启动      →  FDE_DB_PASSWORD='***' ./scripts/start-all.sh
浏览器访问        →  启动日志里带 ?token= 的授权地址
```

---

## 1. 目录约定

全部构建产物与运行数据都落在仓库内的 `runtime/`（已 gitignore，不入库）：

```text
fde-platform-local/
├── runtime/                        # 构建产物与运行数据（不入库）
│   ├── src/                        # DSH 官方源码 + 依赖 + 构建中间物（约 2.7 GB）
│   ├── dsh/                        # 【运行期】dsh 安装目录（启动器 + 全部 lib/ + 前端 dist）
│   ├── dsh-home/                   # 【运行期】本项目专属 DSH_HOME
│   │   └── profiles/web/           #   本项目 profile（bundles = 官方 web + 本插件）
│   ├── agents-home/                # 【运行期】本项目专属 DSH_AGENTS_HOME（不碰 ~/.agents）
│   └── logs/                       # dsh-web.log / backend.log
├── fde-workbench-bundle/           # 插件源码；setup 会在这里生成 lib/
├── workbench/                      # Java 后端
└── scripts/
    ├── setup-runtime.sh            # 一次性（可重复执行）：构建 dsh + 插件 + 生成 profile
    ├── start-all.sh                # 启动 dsh web + Java 后端（同一进程组，Ctrl+C 一起停）
    ├── start-backend.sh            # 单独启动后端（start-all 复用其变量约定）
    └── local.env                   # 本机私有变量（数据库密码等，不入库）
```

| 位置 | 作用 | 是否必须保留 |
|---|---|---|
| `runtime/src` | 构建工作区（含 `node_modules`） | 保留可让二次执行秒级完成；删除后重新全量构建 |
| `runtime/dsh` | **启动用的 dsh 安装目录**，`start-all.sh` 只依赖它 | 是否可运行取决于它 |
| `runtime/dsh-home` | 会话、工作区、storage、凭据 | 删除 = 清空本项目所有 dsh 数据（重建 profile） |
| `runtime/agents-home` | 项目内 agent 配置（`~/.agents` 的替代） | 可删除 |

> `runtime/dsh` 是**自包含**的 dsh 安装目录：`apps/cli/lib/bin.js` 启动器 + 所有依赖包的 `lib/` +
> `apps/web/dist` 前端页面 + 第三方 `node_modules` 全在里面，**不依赖仓库外的任何全局 node_modules**。
> 包与包之间是**相对路径软链**（`packages/bundle/web-app/node_modules/@deepseek-ai/dsh-web-frontend → ../../../../../apps/web`），
> 所以整个 `runtime/dsh` 目录可以原样搬走（见 §6）。

---

## 2. 目标机前置条件

| 项 | 要求 | 检查命令 | 缺失时的现象 |
|---|---|---|---|
| Node.js | **≥ 24.9.0**（`apps/cli/package.json` 的 `engines`） | `node -v` | `setup-runtime.sh` 第一步直接报版本过低 |
| 包管理器 | corepack（Node 自带）或 pnpm 11 | `corepack --version` | 脚本提示执行 `corepack enable` |
| 网络 | 可访问 npm registry 与 github.com（首次构建） | `curl -sI https://registry.npmjs.org` | 脚本报「无法访问 npm registry 或 github.com」 |
| 磁盘 | `runtime/` 所在分区 ≥ 8 GB（首次构建后约 5 GB） | `df -h .` | 脚本报磁盘空间不足 |
| git（可选） | 用于按 tag 拉源码；离线可用 `DSH_SOURCE_DIR` 代替 | `git --version` | 提示安装 git 或改用 `DSH_SOURCE_DIR` |
| rsync | macOS 自带；Linux 需 `apt install rsync` | `rsync --version` | 脚本报「未找到 rsync」 |
| JDK + Maven | 只有跑 Java 后端时需要（`start-backend.sh` 会在缺 jar 时自动 `mvn package`） | `mvn -v` | 后端启动失败 |
| MySQL | 只有跑 Java 后端时需要，`FDE_DB_PASSWORD` 必填 | `/api/health` 里 `db=ok` | 后端能起但 `/api/health` 返回 `db=down` |

平台：macOS / Linux 用 `.sh`；Windows 用 Git Bash 或 WSL（任选其一即可）。

### 2.1 MySQL 密码怎么给

脚本**不保存任何密码**。三种给法任选其一：

```bash
# 1) 命令行环境变量（只在本次进程有效）
FDE_DB_PASSWORD='***' ./scripts/start-all.sh

# 2) scripts/local.env（已 gitignore，最省事）
cp scripts/local.env.example scripts/local.env   # 填入 FDE_DB_PASSWORD=***
./scripts/start-all.sh

# 3) 只要插件界面、不连数据库时
FDE_NO_BACKEND=1 ./scripts/start-all.sh
```

---

## 3. 首次构建

```bash
cd fde-platform-local
./scripts/setup-runtime.sh
```

脚本按固定 tag `dsh-v0.2.2-rc.5` 完成 7 步，**任一步失败都会打印「哪一步、怎么修」**：

| 步骤 | 做什么 | 幂等行为 | 实测耗时（Apple Silicon，pnpm store 已预热） |
|---|---|---|---|
| 1/7 前置检查 | Node / corepack / 网络 / 磁盘 | 每次执行 | < 5 s |
| 2/7 准备源码 | `git clone --depth 1 --branch dsh-v0.2.2-rc.5`；已有则复用 | 已有 `runtime/src` 直接复用 | 首次 10~60 s（取决于网络） |
| 3/7 安装依赖 | `corepack pnpm install --frozen-lockfile` | 已安装时 `Already up to date`（约 1 s） | 首次 20~40 s（store 已预热），冷启动 3~10 分钟 |
| 4/7 构建宿主包 | `tsx scripts/compile-referenced-projects.ts host-libraries` | tsbuildinfo 使二次执行很快 | 首次 2~4 分钟 |
| 5/7 客户端包 + 前端 | `tsc -b tsconfig.client.json` → `tsdown --env.DSH_BUILD_FACE client` → `vite build`（`@deepseek-ai/dsh-web-frontend`） | 已编译则跳过 tsc | 首次 1.5~3 分钟 |
| 6/7 产出 `runtime/dsh` | `rsync --copy-dest=<pnpm store>` 硬链接拷贝 | 每次增量同步 | 首次 20~60 s，之后 10~30 s |
| 7/7 构建插件 + 装 profile | `fde-workbench-bundle/scripts/build.sh` → `dsh plugin --profile web add <插件目录>` | 重复 `add` 无副作用 | 20~60 s |

产物体积参考（本机实测）：`runtime/src` 约 **2.7 GB**，`runtime/dsh` 约 **2.6 GB**（其中 `node_modules` 约 2.2 GB，
经 rsync `--copy-dest` 与 pnpm store 硬链接共享磁盘块，实际额外占用远小于 2.7 GB）。
增量重跑整体约 **1 分钟**（实测 50~56 s）。

### 3.1 自定义

| 变量 | 作用 | 默认 |
|---|---|---|
| `DSH_TAG` | DSH 源码 tag | `dsh-v0.2.2-rc.5` |
| `DSH_REPO` | DSH 源码仓库地址 | `https://github.com/deepseek-ai/deepseek-harness` |
| `DSH_SOURCE_DIR` | 已有的 DSH 源码副本（离线/内网）；脚本只复制源码，依赖与产物现场重建 | 空（走 git clone） |
| `FDE_RUNTIME_DIR` | 运行时根目录 | `<仓库>/runtime` |

### 3.2 构建做了什么（为什么这样排）

```text
pnpm install
  └─ 依赖齐全（workspace 包之间是软链，第三方在 node_modules）
host-libraries                     ← 第 4 步
  ├─ generate-typert（生成 .typert 产物）
  ├─ tsc -b tsconfig.host.json     → 各包 lib/types/**（宿主半宿源码）
  └─ tsdown --env.DSH_BUILD_FACE host → apps/cli/lib/bin.js 等运行入口
client（tsc -b tsconfig.client.json 视情况执行）
  └─ tsdown --env.DSH_BUILD_FACE client → 浏览器端 lib/client.js（Client 半）
vite build（@deepseek-ai/dsh-web-frontend）→ apps/web/dist/index.html   ← web-app 从 dist/index.html 找页面
```

两个必须知道的顺序/时序约束（都是实测踩到的）：

1. **host 必须在 client 之前**：`tsc -b tsconfig.client.json` 发现源码有变化时会清空该包整个 `lib/`，
   把 host 阶段刚产出的 `lib/index.js` 一起删掉；此时 client 阶段的 tsdown 会报
   `UNRESOLVED_ENTRY: Cannot resolve entry module lib/types/index.js`。脚本已把两个阶段串行化，
   并在 client 阶段失败时自动重试一次。
2. **前端 dist 不可省**：`web-app` 通过 `@deepseek-ai/dsh-web-frontend/package.json` 定位 `apps/web/dist/index.html`。
   没有它，服务能起、端口能连，但页面 404。

脚本**没有修改 DSH 官方源码**，`runtime/src` 与上游 tag 逐字节一致。

---

## 4. 启动

```bash
cd fde-platform-local
FDE_DB_PASSWORD='***' ./scripts/start-all.sh
```

启动成功后终端打印：

```text
================================================================
 启动完成
---------------------------------------------------------------
 dsh web 进程 PID   xxxxx   （端口响应 HTTP 401，无 token 访问返回 401 属正常）
 后端      PID      xxxxx
 后端健康检查       HTTP 200  {"status":"ok","db":"ok",...}
---------------------------------------------------------------
 浏览器首次访问请用下面的授权地址（带一次性 token，每次启动都不同）：

   http://127.0.0.1:3080/?token=***

 打开后会种下 cookie 并跳到：http://127.0.0.1:3080/
---------------------------------------------------------------
 日志：runtime/logs/dsh-web.log
       runtime/logs/backend.log
 停止：Ctrl+C（会同时停掉两个进程）
================================================================
```

要点：

- **必须用带 `?token=` 的授权地址首次打开**。token 是进程级、每次启动重新生成，不能写死进书签或文档。
  打开一次后浏览器会种下 cookie，之后直接访问 `http://127.0.0.1:3080/` 即可。
- 不带 token / cookie 的任何请求返回 **401** 纯文本：`dsh web authentication required; reopen the URL printed by dsh web.`
- 有浏览器 cookie 后，`curl http://127.0.0.1:3080` 会得到 401；这不代表服务坏了，用带 token 的地址访问才能拿到页面。
- `Ctrl+C`（或 `kill` 本脚本进程 / 终端关闭）会把 dsh web 与 Java 后端**一起**停掉。

### 4.1 启动参数

| 变量 | 默认 | 说明 |
|---|---|---|
| `FDE_DSH_PORT` | `3080` | dsh web 端口 |
| `FDE_DSH_HOST` | `127.0.0.1` | 绑定地址（只支持具体地址；`0.0.0.0` 会被 dsh 拒绝） |
| `FDE_SERVER_PORT` | `8081` | Java 后端端口 |
| `FDE_NO_BACKEND` | `0` | `1` = 只起 dsh web |
| `FDE_DSH_OPEN` | `0` | `1` = 启动后自动用默认浏览器打开授权地址 |
| `FDE_RUNTIME_DIR` | `<仓库>/runtime` | 运行时根目录 |
| `--wait` | — | 就绪后打印 `READY dsh=<pid> backend=<pid> url=<地址>` 并退出（供自动化使用，子进程不随脚本退出） |

```bash
# 插件开发时只起 dsh，占用另一个端口，不打扰常规实例
FDE_NO_BACKEND=1 FDE_DSH_PORT=3081 ./scripts/start-all.sh
```

### 4.2 profile 是怎么来的

```text
$DSH_HOME/profiles/web/
├── package.json        dsh.profile.bundles = ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "@local/dsh-workbench-bundle"]
│                       dependencies: { "@local/dsh-workbench-bundle": "link:<仓库>/fde-workbench-bundle" }
├── cordis.patch.yml    本项目自己的覆盖层（可选）
├── pnpm-workspace.yaml dsh 生成的 pnpm 设置（hoisted 链接器，profiles 所需）
└── node_modules/       pnpm 生成；@local/dsh-workbench-bundle 是指向仓库插件目录的软链
```

- profile 由 dsh **首次使用时按官方模板初始化**（`web → dsh-base + dsh-web-app`），
  再由官方命令 `dsh plugin --profile web add <插件目录>` 把本插件装进去并自动选中为 bundle。
  脚本**不直接改**插件的 `package.json`（那是源码），也不把插件装进任何全局 profile。
- 插件是否被浏览器加载取决于 **Host 行是否挂载**：client-modules 扫描已挂载的 Host 行，
  读其 `package.json` 的 `dsh.client.platform === "web"` 与 `exports["./client"]`。
  本插件的 `cordis.patch.yml` 同时插入 `fde-workbench`（Host）与 `fde-workbench-client` 两行，
  所以两行必须始终成对存在。
- 安装完成后脚本会自检 bundles / 依赖链接 / Client bundle 是否存在，不通过则给出具体原因。

### 4.3 模型 provider

没有模型 Key 时 **界面能正常打开、插件入口可用，但会话里发消息不会得到回复**。
默认 provider 由 `dsh-base` 挂载：`llm-deepseek` 读取 `DEEPSEEK_API_KEY` 环境变量
（或本 `DSH_HOME` 内的凭据）。给启动命令加一个环境变量即可：

```bash
DEEPSEEK_API_KEY='***' FDE_DB_PASSWORD='***' ./scripts/start-all.sh
```

也可以后续在 Web 界面的「设置 → 模型」里配置（配置写在本项目自己的 `runtime/dsh-home` 内，不影响用户本机 DSH）。

---

## 5. 升级 / 重建 / 卸载

| 目的 | 做法 |
|---|---|
| 只更新插件代码 | `cd fde-workbench-bundle && DSH_SRC=<仓库>/runtime/src bash scripts/build.sh`，然后重启 `start-all.sh`（浏览器端已加载的旧 bundle 需刷新页面） |
| 重新生成本项目 profile | 停掉服务 → `rm -rf runtime/dsh-home/profiles/web` → `./scripts/setup-runtime.sh` |
| 升级 DSH 版本 | 改脚本里的 `DSH_TAG`（或用 `DSH_TAG=<新tag>` 执行），**同时删掉 `runtime/src` 与 `runtime/dsh`** 后重跑 `setup-runtime.sh`；升级后必须完整走一遍 §7 的验收 |
| 只重建 DSH、不动 profile/会话 | `rm -rf runtime/src runtime/dsh` → `./scripts/setup-runtime.sh`（profile 与 dsh-home 保留） |
| 清空本项目所有 dsh 数据 | 停服务 → `rm -rf runtime/dsh-home runtime/agents-home` |
| 完全卸载运行时 | 停服务 → `rm -rf runtime` |

> 升级 DSH 属于「版本变更」，按 [architecture.md §13.4](architecture.md#134-约束) 的要求，
> 需要在 `docs/` 里记录新版本与验收结论（目前固定 `dsh-v0.2.2-rc.5`）。

---

## 6. 换机器部署

**分发方式：目标机现场构建**。仓库只带源码、脚本与锁文件，**不带** `runtime/`。

```bash
# 1) 目标机：装 Node ≥ 24.9，corepack enable
node -v && corepack --version

# 2) 拿到仓库（git clone / 压缩包解压均可）
cd fde-platform-local

# 3) 现场构建（首次 5~15 分钟，取决于网络）
./scripts/setup-runtime.sh

# 4) 准备 scripts/local.env（数据库密码）后启动
./scripts/start-all.sh
```

如果目标机**无法访问 GitHub**（只在内网、只有 npm 镜像）：

```bash
# 把一份 DSH 源码副本（任意一份 tag 为 dsh-v0.2.2-rc.5 的 checkout）拷到目标机
DSH_SOURCE_DIR=/path/to/deepseek-harness ./scripts/setup-runtime.sh
```

如果连 npm registry 也访问不了（完全离线）：先在能联网的机器上用**相同操作系统与 CPU 架构**构建一份
`runtime/dsh`，再整体拷过去（见下）。

### 6.1 整体搬迁 `runtime/dsh`（可选）

`runtime/dsh` 是自包含的，可以整体搬走；但它内含**当前平台的原生模块**（如 `node-pty`），
所以**只能在同 OS / 同架构之间搬**（macOS arm64 → macOS arm64、Linux x64 → Linux x64）。

```bash
tar -czf dsh-runtime-$(uname -s)-$(uname -m).tgz runtime/dsh
# 目标机：
tar -xzf dsh-runtime-*.tgz
```

搬过去后仍需在目标机执行一次 `setup-runtime.sh` 生成 profile 与插件安装（会复用 `runtime/dsh`）。
注意：`--copy-dest` 硬链接可能让 `runtime/dsh` 与 pnpm store 共享 inode，
用 `tar`（而非支持硬链接保留的特殊工具）打包不会改变内容，可放心打包。

---

## 7. 验收清单

```bash
# 记录用户环境基线（必须保持不变）
ls -la ~/.dsh > /tmp/before.txt

./scripts/setup-runtime.sh
FDE_DB_PASSWORD='***' ./scripts/start-all.sh &
sleep 30
curl -s -o /dev/null -w 'dsh:%{http_code}\n' http://127.0.0.1:3080        # 期望 401（无 token 属正常）
curl -s http://127.0.0.1:8081/api/health                                  # 期望 db=ok
ls -la ~/.dsh > /tmp/after.txt && diff /tmp/before.txt /tmp/after.txt && echo "~/.dsh 未被修改"

# 用启动日志里的授权地址访问（打码显示）
grep -oE '^dsh web: https?://[^ ]+' runtime/logs/dsh-web.log | sed 's/token=.*/token=***/'

# 停服务后端口应释放
lsof -nP -iTCP:3080 -sTCP:LISTEN; lsof -nP -iTCP:8081 -sTCP:LISTEN
```

界面验收（需人工在浏览器确认）：打开授权地址 → 进入 DSH Web 界面 → **侧栏全局面板列表第一行出现「Web 工作台」**
→ 点击进入工作台整页 → 面板内的节点会话可见、可输入。

---

## 8. 常见故障

### 8.1 离线 / 网络不可达

| 现象 | 原因 | 处理 |
|---|---|---|
| `无法访问 npm registry 或 github.com` | 断网或代理未配置 | 配 `HTTPS_PROXY`；或把源码放本地后用 `DSH_SOURCE_DIR=<目录>` 执行 |
| `pnpm install` 卡住或报 `ERR_PNPM_META_FETCH_FAIL` | registry 不通 / 无权限 | 设置 `npm config set registry <内网镜像>`；确认镜像有 `@deepseek-ai/*` 与 `@earendil-works/*` |
| `git clone` 失败 | GitHub 不可达 | 用 `DSH_SOURCE_DIR`，或 `DSH_REPO=<内网 git 地址>` |
| 报 `TypeScript type stripping is unavailable` | `NODE_OPTIONS` 里带了 `--no-experimental-strip-types` | 取消该选项（脚本已在内部清空 `NODE_OPTIONS`，若仍出现请检查 `.npmrc`/shell 配置） |
| `node-pty` / `koffi` 编译失败 | 缺编译工具链 | macOS：`xcode-select --install`；Debian/Ubuntu：`apt install build-essential python3` |

### 8.2 端口被占用

```text
[错误] 端口 3080 已被占用（dsh web 需要它）。
[错误] 请换端口重试，例如：FDE_DSH_PORT=3081 ./scripts/start-all.sh
```

- 查看占用者：`lsof -nP -iTCP:3080 -sTCP:LISTEN`。
- 用户本机如果同时开着 **Desktop 版 DSH**（默认 57320 端口），与本项目不冲突，无需关闭。
- 后端端口冲突同理：`FDE_SERVER_PORT=8082`。

### 8.3 授权链接 / 401

- 打开 `http://127.0.0.1:3080/` 得到纯文本 401：这是正常鉴权结果，请用启动日志里**带 `?token=` 的地址**访问。
- 授权地址找不到：`grep '^dsh web:' runtime/logs/dsh-web.log | tail -1`。
- 授权地址打不开 / 换浏览器后 401：token 与 cookie 属于**某一次启动**，重启服务后旧链接全部失效，重新取新地址。
- 想把授权地址换成可收藏的地址：首次授权后 cookie 有效期内直接访问根地址即可；cookie 失效后重新走授权地址。

### 8.4 profile 未生成 / 插件没出现

| 现象 | 原因 | 处理 |
|---|---|---|
| `start-all.sh` 报「未找到本项目 profile」 | 没跑过 `setup-runtime.sh`，或 `runtime/dsh-home` 被删 | 重新执行 `./scripts/setup-runtime.sh` |
| 启动日志出现 `skipping profile bundle "@local/dsh-workbench-bundle"` | 插件的 `lib/` 缺失 / bundle 未选中 | 重跑 `setup-runtime.sh`（第 7 步会重建并自检） |
| 侧栏看不到「Web 工作台」 | 浏览器缓存了旧的 `window.__DSH_BOOT__`；或插件 Client bundle 未重建 | 强制刷新（Cmd/Ctrl+Shift+R）；重跑 `setup-runtime.sh` 后重启 |
| 改了插件代码但界面没变 | 浏览器端 bundle 是启动时读取的 | 重新 `build.sh` → 重启 `start-all.sh` → 强刷 |
| 页面 404 / 白屏 | `apps/web/dist` 缺失（前端未构建） | 重跑 `setup-runtime.sh`；确认 `runtime/dsh/apps/web/dist/index.html` 存在 |

### 8.5 后端相关

| 现象 | 原因 | 处理 |
|---|---|---|
| 报「未设置 FDE_DB_PASSWORD」 | 没给密码 | 见 §2.1 |
| `/api/health` 返回 `db=down` | 数据库不可达 / 密码错误 | 检查 `FDE_DB_URL`、`FDE_DB_PASSWORD`；MySQL 是否允许目标机连接 |
| 120 秒内后端未就绪 | 首次启动要 `mvn package`（较慢）；或端口冲突 | 看 `runtime/logs/backend.log`；先单独跑一次 `./scripts/start-backend.sh` 预构建 |

---

## 9. 已验证 / 未验证

验证环境：macOS arm64、Node v24.21.0、pnpm 11.28.5、DSH `dsh-v0.2.2-rc.5`、无头 Chrome（Playwright）。

| 项 | 状态 | 证据 |
|---|---|---|
| `setup-runtime.sh` 构建出可启动的 `runtime/dsh` | ✅ 已验证 | 退出码 0；`dsh --version` → `0.2.2-rc.5`；`runtime/dsh` 2.6 GB |
| 重复执行幂等 | ✅ 已验证 | 第二次退出码 0，耗时 56 s（宿主构建走 tsbuildinfo，客户端跳过 tsc） |
| 本项目 profile 加载插件（不装进全局 profile） | ✅ 已验证 | `dsh --dump-config --profile web` 输出末尾含 `# == @local/dsh-workbench-bundle` 与 `fde-workbench` / `fde-workbench-client` 两行 |
| 插件 Client bundle 下发给浏览器 | ✅ 已验证 | 首页 `window.__DSH_BOOT__` 含 `@local/dsh-workbench-bundle` 条目；`/plugins/??@local/dsh-workbench-bundle/client.js` 返回 200 |
| `dsh web` 启动 + 授权链接换 cookie | ✅ 已验证 | 无 token → 401；带 `?token=` → 303 → 200，页面 `<title>DSH Local Build</title>` |
| 侧栏入口「Web 工作台」 | ✅ 已验证 | 侧栏全局面板列表**第一项**（在「插件」之上），截图见验证记录 |
| 工作台面板可打开 | ✅ 已验证 | 点击后中央区域显示「返回 / Web 工作台」标题栏与「节点会话」区 |
| 面板内嵌入会话的消息流 / 输入框 | ❌ **未通过**（见 §9.1） | 面板显示会话 id 与标题，但会话内容区为空（`data-fde-workbench-embed` 未渲染，界面上也没有报错块） |
| Java 后端与 dsh 同时启动、一起停止 | ✅ 已验证 | `/api/health` → `{"db":"ok"}`；SIGTERM 后 3080 / 8081 均释放、无残留进程 |
| `~/.dsh`、`~/.agents` 未被读写 | ✅ 已验证 | 安装与启动前后 `ls -la` 对比逐字节一致 |
| 错误提示（端口占用 / 缺密码 / 未 setup / 非法参数） | ✅ 已验证 | 四种情况均输出对应中文提示且不启动一半进程 |
| Windows 原生命令行 | ⚠️ 未验证 | 请用 Git Bash / WSL；`.ps1` 未提供 |
| 从零（空 pnpm store）的网络下载耗时 | ⚠️ 未实测 | 本机 store 已预热；冷启动预计 3~10 分钟 |
| 跨机器整目录搬迁 `runtime/dsh` | ⚠️ 未实测 | 依赖同 OS/架构 |

### 9.1 已知问题：面板内嵌会话内容为空（需插件侧修复）

现象与定位（T0-4 实测，**与运行脚本无关**）：

- 工作台面板能正常渲染：`data-fde-workbench-panel`、`data-fde-workbench-session-view`、
  `data-fde-workbench-session-id` 都存在，且正确选出了一个会话 id。
- 但会话视图里只有标题行（「节点会话 + 会话 id + 刷新会话列表」），其下的内容区高度 768 px、**0 个子节点**：
  说明 `renderFactorySlot('workbench.node.chat', { reference })` 输出为空。
- 没有 `data-fde-workbench-error`（不是插件自己的失败态分支），控制台也没有任何报错。
- 对照：官方主会话页（未进工作台时）的输入框正常渲染，所以 `conversation.content` 本身可用。

依据 `packages/client/ui-renderer/src/client/scoped-slots.tsx` 的 `FactoryOutlet` 实现，插件把**局部槽位名**
当成工厂名调用了：

```ts
// fde-workbench-bundle/src/client/session/NodeSessionView.tsx（当前实现）
return renderFactorySlot('workbench.node.chat.conversation' as 'workbench.node.chat', { reference }, { … })
//                      ^ 这是局部槽位，不是工厂；host.factoryOf('…conversation') 未定义 → 返回 fallback（null）
```

工厂的根组件拿到的渲染能力一次只能渲染**一个工厂**（要么渲染别的工厂、要么用 `renderFactorySlot` 的
`slots` 选项换掉它自己的局部槽位组件，不能退回来再渲染自己的局部槽位）。官方 `ui-subagent` 的写法是：
外层注册一个局部槽位（`sidebar.chat.conversation`）由"宿主入口"渲染，槽位组件内部再 `renderFactorySlot('conversation.content', …)`，
绕开了"工厂内部渲染自己的局部槽位"这一限制。

修复方向（留给插件任务处理，不在 T0-4 范围内）：照搬官方 `sidebar-chat` 的两层结构——工作台面板里注册一个
`main` 内的局部槽位承载 `SessionProvider` + `conversation.content`，而不是在 `workbench.node.chat` 工厂里
再渲染 `workbench.node.chat.conversation`。
