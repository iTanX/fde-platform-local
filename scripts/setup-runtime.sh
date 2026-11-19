#!/usr/bin/env bash
# =============================================================================
# Web 工作台 —— 一次性准备「自带可携带 dsh」运行环境
#
# 目标机只需安装 Node（≥ 24.9）并能访问 npm registry，执行本脚本后即可得到：
#   runtime/src                 DSH 官方源码（固定 tag dsh-v0.2.2-rc.5）
#   runtime/dsh                 构建产物（apps/cli 启动器 + 全部依赖包的 lib/ + 前端 dist）
#   runtime/dsh-home/profiles/web  本项目专属 profile（bundles = 官方 web bundle + 本插件）
#
# 用法：
#   ./scripts/setup-runtime.sh                # 正常流程
#   DSH_SOURCE_DIR=/tmp/dsh-src ./scripts/setup-runtime.sh
#                                            # 离线：直接复用一份已存在的 DSH 源码副本，跳过 git 拉取
#
# 可配环境变量（一般不用改）：
#   DSH_TAG         官方版本 tag，默认 dsh-v0.2.2-rc.5
#   DSH_REPO        DSH 源码仓库地址，默认 https://github.com/deepseek-ai/deepseek-harness
#   DSH_SOURCE_DIR  已有的源码副本（离线/内网场景）；为空则从 DSH_REPO 拉取
#   FDE_RUNTIME_DIR 运行时目录，默认 <仓库>/runtime
#
# 幂等：任意一步已经完成时会跳过；可反复执行。
# 插件源码复制到本项目运行目录后构建和安装，profile 也保存在该目录。
# Windows 请用 Git Bash / WSL，或改用 scripts/setup-runtime.ps1。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${FDE_RUNTIME_DIR:-$REPO_ROOT/runtime}"
RUNTIME_DIR="$(mkdir -p "$RUNTIME_DIR" && cd "$RUNTIME_DIR" && pwd -P)"
SRC_DIR="$RUNTIME_DIR/src"
DSH_OUT="$RUNTIME_DIR/dsh"
DSH_HOME="$RUNTIME_DIR/dsh-home"
AGENTS_HOME="$RUNTIME_DIR/agents-home"
PROFILE_DIR="$DSH_HOME/profiles/web"
PLUGIN_DIR="$RUNTIME_DIR/workbench-bundle"
PLUGIN_NAME="@local/dsh-workbench-bundle"
DSH_TAG="${DSH_TAG:-dsh-v0.2.2-rc.5}"
DSH_REPO="${DSH_REPO:-https://github.com/deepseek-ai/deepseek-harness}"
STAGE_FILE="$RUNTIME_DIR/.setup-stage"

MIN_NODE_MAJOR=24
MIN_NODE_MINOR=9
MIN_FREE_GB=8

# ---------------------------------------------------------------------------
# 输出与错误处理：每一步失败都要说清楚「哪一步、怎么修」
# ---------------------------------------------------------------------------
STEP="启动检查"
trap 'echo "" >&2; echo "[失败] 上一步出错：$STEP" >&2; echo "       修复后可直接重新执行 ./scripts/setup-runtime.sh（已完成的步骤会自动跳过）。" >&2' ERR

info() { echo "[信息] $*"; }
warn() { echo "[警告] $*" >&2; }
die() {
  echo "[错误] $*" >&2
  exit 1
}
step() {
  STEP="$1"
  echo ""
  echo "== $1 =="
  mkdir -p "$RUNTIME_DIR"
  echo "$1" > "$STAGE_FILE"
}

# ---------------------------------------------------------------------------
# 0. 让所有子进程都只认仓库内的 home（防呆：绝不落到 ~/.dsh / ~/.agents）
# ---------------------------------------------------------------------------
export DSH_HOME
export DSH_AGENTS_HOME="$AGENTS_HOME"
export PYTHONNOUSERSITE="${PYTHONNOUSERSITE:-1}"
mkdir -p "$RUNTIME_DIR" "$DSH_HOME" "$AGENTS_HOME"

# ---------------------------------------------------------------------------
# 1. 前置检查：Node 版本、corepack/pnpm、网络、磁盘空间
# ---------------------------------------------------------------------------
step "1/7 前置检查（Node / pnpm / 网络 / 磁盘）"

command -v node >/dev/null 2>&1 \
  || die "未找到 node。请先安装 Node.js ≥ ${MIN_NODE_MAJOR}.${MIN_NODE_MINOR}（推荐 nvm 或 https://nodejs.org 的 LTS 安装包）后重试。"
NODE_VERSION="$(node -p 'process.versions.node')"
NODE_MAJOR="${NODE_VERSION%%.*}"
NODE_MINOR="$(printf '%s' "$NODE_VERSION" | cut -d. -f2)"
if [[ "$NODE_MAJOR" -lt "$MIN_NODE_MAJOR" ]] \
  || { [[ "$NODE_MAJOR" -eq "$MIN_NODE_MAJOR" ]] && [[ "$NODE_MINOR" -lt "$MIN_NODE_MINOR" ]]; }; then
  die "Node 版本过低：当前 v${NODE_VERSION}，DSH ${DSH_TAG} 要求 ≥ ${MIN_NODE_MAJOR}.${MIN_NODE_MINOR}.0。请升级 Node 后重试。"
fi
info "Node v${NODE_VERSION} 符合要求（≥ ${MIN_NODE_MAJOR}.${MIN_NODE_MINOR}.0）"

if ! command -v pnpm >/dev/null 2>&1 && ! command -v corepack >/dev/null 2>&1; then
  die "未找到 corepack 或 pnpm。请安装其中之一后重试。"
fi

FREE_GB="$(node -e '
  // 取 runtime/ 所在分区的可用空间（GB）；取得不到时输出 0（不阻断）
  const fs = require("node:fs")
  try { const s = fs.statfsSync(process.argv[1]); console.log(Math.floor(s.bavail * s.bsize / (1024 ** 3))) } catch { console.log(0) }
' "$RUNTIME_DIR")"
if [[ "${FREE_GB:-0}" -gt 0 ]] && [[ "$FREE_GB" -lt "$MIN_FREE_GB" ]]; then
  die "磁盘空间不足：${RUNTIME_DIR} 所在分区仅剩 ${FREE_GB} GB，首次构建约需 ${MIN_FREE_GB} GB（依赖 + 构建产物）。请清理后重试。"
fi
[[ "${FREE_GB:-0}" -gt 0 ]] && info "磁盘可用空间：${FREE_GB} GB"

# 网络：首次构建必须能访问 npm registry 与 GitHub；离线时提示改用 DSH_SOURCE_DIR
network_ok() {
  node -e '
    // 依次探测 npm registry 与 GitHub 的连通性（3 秒超时）
    const targets = ["https://registry.npmjs.org/pnpm", "https://github.com"]
    const probe = url => new Promise((resolve) => {
      const req = require("node:https").get(url, { timeout: 3000 }, (res) => { res.resume(); resolve(true) })
      req.on("timeout", () => { req.destroy(); resolve(false) })
      req.on("error", () => resolve(false))
    })
    Promise.all(targets.map(probe)).then(all => process.exit(all.every(Boolean) ? 0 : 1))
  ' 2>/dev/null
}
if network_ok; then
  info "网络连通（npm registry / github.com 可达）"
else
  if [[ -n "${DSH_SOURCE_DIR:-}" ]]; then
    warn "网络不可用，但已指定 DSH_SOURCE_DIR=${DSH_SOURCE_DIR}，将按离线方式构建。"
  else
    die "无法访问 npm registry 或 github.com。首次构建需要联网；离线/内网环境请：
       1) 把一份 DSH ${DSH_TAG} 源码副本拷到本机；
       2) 执行：DSH_SOURCE_DIR=<源码副本路径> ./scripts/setup-runtime.sh
       详见 docs/runtime.md「常见故障 · 离线」。
       若只是代理问题，请先配置 HTTPS_PROXY / npm proxy 后重试。"
  fi
fi

# ---------------------------------------------------------------------------
# 2. 准备 DSH 源码：复用已有的，或按固定 tag 拉取
# ---------------------------------------------------------------------------
step "2/7 准备 DSH 源码（tag ${DSH_TAG}）"

reset_dir() {
  # 安全删除：只允许删除 runtime/ 之内的目录
  local target="$1"
  case "$target" in
    "$RUNTIME_DIR"/*) rm -rf -- "$target" ;;
    *) die "拒绝删除 runtime/ 之外的路径：$target" ;;
  esac
}

if [[ -d "$SRC_DIR" ]] && [[ -f "$SRC_DIR/package.json" ]] && [[ -d "$SRC_DIR/node_modules" ]]; then
  info "复用已有源码：$SRC_DIR"
elif [[ -n "${DSH_SOURCE_DIR:-}" ]]; then
  [[ -f "$DSH_SOURCE_DIR/package.json" ]] \
    || die "DSH_SOURCE_DIR=$DSH_SOURCE_DIR 不是 DSH 源码目录（缺 package.json）。请检查路径后重试。"
  info "从本地副本复制源码：$DSH_SOURCE_DIR -> ${SRC_DIR}（依赖由本脚本重新安装）"
  reset_dir "$SRC_DIR"
  # 只复制「源码」，不复制任何依赖或产物：
  #   - node_modules：目标机架构可能不同（原生依赖），且可能与锁文件不一致；
  #   - lib/、dist/：旧构建产物，会让增量构建误以为已完成；
  #   - .git、.agents/tmp：与构建无关。
  mkdir -p "$SRC_DIR"
  (cd "$DSH_SOURCE_DIR" && tar -cf - \
      --exclude=node_modules --exclude=.git --exclude='*/lib' --exclude='*/dist' --exclude=.agents/tmp .) \
    | (cd "$SRC_DIR" && tar -xf -)
elif command -v git >/dev/null 2>&1; then
  if [[ -d "$SRC_DIR/.git" ]]; then
    info "已有源码仓库，更新到 $DSH_TAG"
    git -C "$SRC_DIR" fetch --depth 1 origin "refs/tags/$DSH_TAG:refs/tags/$DSH_TAG" \
      || warn "fetch 失败，继续使用现有源码"
    git -C "$SRC_DIR" checkout -q "$DSH_TAG" || die "切换到 $DSH_TAG 失败。可删除 $SRC_DIR 后重试。"
    STEP="2/7 更新 DSH 源码"
  else
    info "克隆 ${DSH_REPO}（tag ${DSH_TAG}，浅克隆）"
    reset_dir "$SRC_DIR"
    mkdir -p "$RUNTIME_DIR"
    git clone --depth 1 --branch "$DSH_TAG" "$DSH_REPO" "$SRC_DIR" \
      || die "克隆 DSH 源码失败。请检查网络/代理/仓库地址（DSH_REPO），或改用 DSH_SOURCE_DIR 指定本地副本。"
  fi
else
  die "未找到 git，且尚未准备 ${SRC_DIR}。请安装 git，或用 DSH_SOURCE_DIR=<源码副本> 重试。"
fi

# 版本核对：源码版本必须与期望 tag 一致，否则插件可能不兼容
SRC_VERSION="$(node -p 'require(process.argv[1]).version' "$SRC_DIR/apps/cli/package.json" 2>/dev/null || echo '未知')"
info "源码版本：${SRC_VERSION}（tag ${DSH_TAG}）"
SRC_SIZE="$(du -sh "$SRC_DIR" 2>/dev/null | cut -f1 || echo '?')"
info "源码体积：$SRC_SIZE"

# ---------------------------------------------------------------------------
# 3. 安装依赖（pnpm install，幂等）
# ---------------------------------------------------------------------------
step "3/7 安装 DSH 依赖（npm registry，首次约数分钟）"

cd "$SRC_DIR"
if ! command -v pnpm >/dev/null 2>&1; then
  # DSH 安装插件时按命令名调用 pnpm。源码就绪后读取其锁定版本，无须全局 shim 或预热缓存。
  PNPM_SPEC="$(node -p 'require(process.argv[1]).packageManager' "$SRC_DIR/package.json")"
  [[ "$PNPM_SPEC" =~ ^pnpm@[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "DSH 源码未声明可识别的 pnpm 版本。"
  SHIM_DIR="$RUNTIME_DIR/.bin"
  mkdir -p "$SHIM_DIR"
  printf '#!/bin/sh\nexec corepack %s "$@"\n' "$PNPM_SPEC" > "$SHIM_DIR/pnpm"
  chmod +x "$SHIM_DIR/pnpm"
  export PATH="$SHIM_DIR:$PATH"
fi
info "包管理器：$(command -v pnpm)"
# 注意：不要在这里设置 NODE_ENV=production，构建需要 devDependencies（typescript/tsdown/vite）
pnpm install --frozen-lockfile \
  || die "pnpm install 失败。常见原因：网络/代理（HTTPS_PROXY）、npm registry 无权限、或 node-pty 等原生依赖编译失败（需要 Xcode Command Line Tools / build-essential）。修复后重试。"
info "依赖安装完成（node_modules 体积：$(du -sh node_modules | cut -f1)）"

# ---------------------------------------------------------------------------
# 4. 构建 DSH：宿主包（含 Typert 代码生成 + 全仓 tsdown），且必须先于客户端包
# ---------------------------------------------------------------------------
step "4/7 构建 DSH 宿主包（Host libraries，最耗时的一步）"

cd "$SRC_DIR"
# NODE_OPTIONS 里的 --no-experimental-strip-types 会让 tsdown 无法加载 TS 配置，这里显式清掉
NODE_OPTIONS="" pnpm exec tsx scripts/compile-referenced-projects.ts host-libraries \
  || die "宿主包构建失败（脚本：scripts/compile-referenced-projects.ts host-libraries）。若报『TypeScript type stripping is unavailable』，请检查 NODE_OPTIONS 是否包含 --no-experimental-strip-types；若是内存不足，请提高 Node 堆上限后重试。"
[[ -f apps/cli/lib/bin.js ]] || die "构建结束但缺少 apps/cli/lib/bin.js，构建未真正完成。请查看上面的日志。"
info "宿主包构建完成（apps/cli/lib/bin.js 已生成）"

# ---------------------------------------------------------------------------
# 5. 构建 DSH 客户端包（Web 前端所需），再构建浏览器页面 dist
# ---------------------------------------------------------------------------
step "5/7 构建 DSH 客户端包与 Web 前端 dist"

cd "$SRC_DIR"
# 客户端包的源码由 tsc -b 编进 lib/types，再由 tsdown 打包成运行期用的 lib/*.js。
# 注意：tsc -b 在「发现源变了」时会先清空该包的 lib/（连上一次的 tsdown 产物一起清），
# 所以客户端阶段必须放在宿主阶段之后（等价于官方 build:lib 的 host → client 顺序）。
if [[ -d packages/client/ui-sidebar/lib/types ]]; then
  info "客户端包已编译（lib/types 齐全），跳过 tsc，直接重新打包"
else
  NODE_OPTIONS="" pnpm exec tsc -b tsconfig.client.json \
    || die "客户端包编译失败（tsc -b tsconfig.client.json）。请查看上面的类型错误。"
fi
# tsdown 会并发构建全部工作区包；某个包报 UNRESOLVED_ENTRY 多半是上一次的 lib/ 被清空所致，
# 因此失败时自动重试一次（第二次此时 lib/types 已稳定，通常即可通过）。
client_bundle() {
  NODE_OPTIONS="" pnpm exec tsdown --config-loader native --env.DSH_BUILD_FACE client
}
if ! client_bundle; then
  warn "客户端包打包失败，自动重试一次（上一次的报错多为 lib/ 被 tsc 清空导致的时序问题）…"
  client_bundle \
    || die "客户端包打包失败（tsdown --env.DSH_BUILD_FACE client，已重试一次）。请检查上面的 UNRESOLVED_ENTRY / 类型错误；可删除 runtime/src 后重新执行本脚本。"
fi
[[ -f packages/client/ui-sidebar/lib/client.js ]] \
  || die "客户端包打包结束但缺少 packages/client/ui-sidebar/lib/client.js，构建未真正完成。"
# 打包浏览器页面：web-app 通过 @deepseek-ai/dsh-web-frontend/package.json 的 dist/index.html 找页面，
# 没有这个文件时服务能起但页面 404
NODE_OPTIONS="" pnpm --filter @deepseek-ai/dsh-web-frontend run build \
  || die "Web 前端构建失败（vite build，包 @deepseek-ai/dsh-web-frontend）。请查看上面的 vite 日志。"
[[ -f apps/web/dist/index.html ]] || die "Web 前端构建结束但缺少 apps/web/dist/index.html，构建未真正完成。"
info "前端 dist 已生成：apps/web/dist（$(du -sh apps/web/dist | cut -f1)）"

# ---------------------------------------------------------------------------
# 6. 产出 runtime/dsh：启动器 + 运行期依赖闭包（含 workspace 软链与第三方依赖）
# ---------------------------------------------------------------------------
step "6/7 产出 runtime/dsh（可搬运的 dsh 运行目录）"

# 保留 pnpm 生成的相对 workspace 软链，使运行目录自包含。
if ! command -v rsync >/dev/null 2>&1; then
  die "未找到 rsync（macOS 自带；Debian/Ubuntu 执行 apt install rsync）。"
fi
[[ ! -L "$DSH_OUT" ]] || die "DSH 运行目录必须是实际目录：$DSH_OUT"
mkdir -p "$DSH_OUT"
info "同步源码与构建产物到 $DSH_OUT ..."
rsync -a --delete --exclude=.git --exclude=.agents/tmp "$SRC_DIR"/ "$DSH_OUT"/

# 运行期必需产物自检（缺一个 dsh web 都起不来）
for required in \
    "$DSH_OUT/apps/cli/lib/bin.js" \
    "$DSH_OUT/packages/bundle/web-app/lib/index.js" \
    "$DSH_OUT/packages/client/ui-sidebar/lib/client.js" \
    "$DSH_OUT/apps/web/dist/index.html"; do
  [[ -f "$required" ]] || die "运行目录缺少必需产物：${required#"$DSH_OUT"/}。请删除 $DSH_OUT 后重新执行本脚本。"
done
# workspace 软链必须仍然指向 runtime/dsh 内部（保证整体可搬运）
if [[ -L "$DSH_OUT/packages/bundle/web-app/node_modules/@deepseek-ai/dsh-web-frontend" ]]; then
  info "workspace 软链校验通过（@deepseek-ai/dsh-web-frontend → apps/web）"
fi
info "runtime/dsh 体积：$(du -sh "$DSH_OUT" | cut -f1)"

# 启动器自检：能打印版本说明 Node 模块解析与 lib/ 都正常
DSH_BIN="$DSH_OUT/apps/cli/lib/bin.js"
DSH_CLI_VERSION="$(cd "$DSH_OUT" && node "$DSH_BIN" --version 2>/dev/null | tail -n 1 || true)"
[[ -n "$DSH_CLI_VERSION" ]] || die "启动器自检失败：node runtime/dsh/apps/cli/lib/bin.js --version 无输出。请查看上面的报错。"
info "启动器自检通过：dsh $DSH_CLI_VERSION"

# ---------------------------------------------------------------------------
# 7. 构建插件并安装进本项目 profile
# ---------------------------------------------------------------------------
step "7/7 构建 fde-workbench-bundle 并安装进 profile"

# 在运行目录构建并安装副本，确保目标机只依赖本次构建的产物。
[[ ! -L "$PLUGIN_DIR" ]] || die "插件安装目录必须是实际目录：$PLUGIN_DIR"
mkdir -p "$PLUGIN_DIR"
FDE_RUNTIME_DIR="$RUNTIME_DIR" DSH_SRC="$SRC_DIR" bash "$REPO_ROOT/scripts/build-plugin.sh" \
  || die "插件构建失败。请查看上面的 tsc/tsdown 日志。"
for artifact in lib/index.js lib/client.js; do
  [[ -f "$PLUGIN_DIR/$artifact" ]] || die "插件构建结束但缺少 ${artifact}。"
done
info "插件产物：lib/index.js（Host 入口）、lib/client.js（$(du -h "$PLUGIN_DIR/lib/client.js" | cut -f1) 浏览器 bundle）"

# profile 由 dsh 首次使用时按官方模板初始化（web → dsh-base + dsh-web-app）；
# 再用官方 `dsh plugin ... add <目录>` 把运行目录中的副本装进去并按 dsh.bundle 自动选中。
cd "$DSH_OUT"
node "$DSH_BIN" plugin --profile web add "$PLUGIN_DIR" \
  || die "插件安装失败（node runtime/dsh/apps/cli/lib/bin.js plugin --profile web add ${PLUGIN_DIR}）。常见原因：profile 目录残留 pnpm 锁、PATH 中没有 pnpm、网络不可用。可删除 ${PROFILE_DIR}/node_modules 与 pnpm-lock.yaml 后重试。"

# 校验实际安装的 Client 文件，以及通过 DSH 官方 profile 解析器加载的 Host 入口。
node --input-type=module - "$PROFILE_DIR" "$PLUGIN_NAME" "$PLUGIN_DIR" "$DSH_OUT/apps/cli/package.json" <<'NODE' \
  || die "profile 安装结果校验失败（见上方原因）。修复后重新执行本脚本。"
import assert from 'node:assert/strict'
import { readFileSync, realpathSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const [profileDir, pluginName, pluginDir, installAnchor] = process.argv.slice(2)
const manifest = JSON.parse(readFileSync(join(profileDir, 'package.json'), 'utf8'))
const bundles = manifest.dsh?.profile?.bundles ?? []
for (const name of ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', pluginName]) {
  assert(bundles.includes(name), `profile 缺少 bundle：${name}`)
}
const installed = join(profileDir, 'node_modules', pluginName)
assert.equal(realpathSync(installed), realpathSync(pluginDir), 'profile 必须链接到本次构建的插件')
const pkg = JSON.parse(readFileSync(join(installed, 'package.json'), 'utf8'))
assert.equal(pkg.dsh?.client?.platform, 'web')
const clientPath = join(installed, pkg.exports['./client'])
assert(statSync(clientPath).isFile(), '缺少插件 Client 产物')

const require = createRequire(installAnchor)
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href)
const { createRuntimeResolution, loadProfileDirectory, PluginPackages } =
  await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-app-boot')).href)
const profile = loadProfileDirectory('dsh', profileDir, installAnchor)
const resolution = await createRuntimeResolution({ installAnchor, profile, home: process.env.DSH_HOME })
const ctx = new Context()
try {
  await ctx.plugin(PluginPackages, { resolution })
  const host = await import(pathToFileURL(join(installed, pkg.exports['.'])).href)
  assert.equal(typeof host.apply, 'function')
  assert.equal(typeof host.name, 'string')
} finally {
  await ctx.fiber.dispose()
}
console.log(`[信息] profile 校验通过：${bundles.join(' + ')}，Host 与 Client 产物可用`)
NODE

# ---------------------------------------------------------------------------
# 完成
# ---------------------------------------------------------------------------
step "完成"
cat <<EOF

[成功] 自带 dsh 运行环境已就绪。

  源码      $SRC_DIR            (DSH $DSH_CLI_VERSION)
  运行目录  $DSH_OUT            $(du -sh "$DSH_OUT" | cut -f1)
  DSH_HOME  $DSH_HOME           （本项目专属，未触碰 ~/.dsh）
  profile   $PROFILE_DIR
  插件      $PLUGIN_DIR

下一步：启动 dsh web 与 Java 后端
  FDE_DB_PASSWORD='<数据库密码>' ./scripts/start-all.sh

首次在浏览器打开需要使用启动日志里带 ?token= 的授权地址（每次启动都会变）。
EOF
