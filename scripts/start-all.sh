#!/usr/bin/env bash
# =============================================================================
# Web 工作台 —— 一键启动「自带 dsh web + Java 后端」
#
# 用法：
#   # 数据库密码建议放 scripts/local.env（不入库），或写在命令行前面：
#   FDE_DB_PASSWORD='<数据库密码>' ./scripts/start-all.sh
#
#   FDE_DSH_PORT=3081 ./scripts/start-all.sh     # 换 dsh web 端口（默认 3080）
#   FDE_SERVER_PORT=8082 ./scripts/start-all.sh  # 换后端端口（默认 8081）
#   FDE_NO_BACKEND=1 ./scripts/start-all.sh      # 只起 dsh web（调试插件时用）
#   ./scripts/start-all.sh --wait                # 启动到「就绪」后打印证书并退出（前台常驻交给调用方/脚本）
#                                                # 仍在运行时输出：READY dsh=<pid> backend=<pid> url=<地址>
#
# 主要环境变量：
#   FDE_DB_PASSWORD / FDE_DB_URL / FDE_DB_USERNAME   与 start-backend.sh 相同（也可写 scripts/local.env）
#   FDE_DSH_PORT        dsh web 端口，默认 3080
#   FDE_DSH_HOST        dsh web 绑定地址，默认 127.0.0.1（本机访问；只支持具体地址，不支持 0.0.0.0）
#   FDE_SERVER_PORT     Java 后端端口，默认 8081
#   FDE_NO_BACKEND=1    跳过 Java 后端
#   FDE_DSH_OPEN=1      启动后自动用默认浏览器打开授权地址（默认不打开，只打印）
#   FDE_KEEP_HOME=1     忽略本机的 DSH_HOME/DSH_AGENTS_HOME 设置，强制只用仓库内 runtime/（默认行为）
#
# 进程管理：本脚本是「前台」进程，Ctrl+C（或 SIGTERM）会同时停掉 dsh web 与 Java 后端。
# 前台挂断（SIGHUP）时同样会清理子进程。
# 安全：DSH_HOME / DSH_AGENTS_HOME 永远指向 <仓库>/runtime 内，绝不读写 ~/.dsh、~/.agents。
# Windows 请用 Git Bash / WSL，或改用 scripts/start-all.ps1。
# =============================================================================
set -uo pipefail

# ---------------------------------------------------------------------------
# 输出函数（参数解析之前就要可用）
# ---------------------------------------------------------------------------
info() { echo "[信息] $*"; }
warn() { echo "[警告] $*" >&2; }
err() { echo "[错误] $*" >&2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${FDE_RUNTIME_DIR:-$REPO_ROOT/runtime}"
DSH_OUT="$RUNTIME_DIR/dsh"
DSH_BIN="$DSH_OUT/apps/cli/lib/bin.js"
PLUGIN_DIR="$REPO_ROOT/fde-workbench-bundle"
PROFILE_DIR="$RUNTIME_DIR/dsh-home/profiles/web"

WAIT_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --wait) WAIT_ONLY=1 ;;
    *) err "未知参数：${arg}（可用：--wait）"; exit 2 ;;
  esac
done

DSH_PORT="${FDE_DSH_PORT:-3080}"
DSH_HOST="${FDE_DSH_HOST:-127.0.0.1}"
BACKEND_PORT="${FDE_SERVER_PORT:-8081}"
LOG_DIR="$RUNTIME_DIR/logs"
BACKEND_LOG="$LOG_DIR/backend.log"
DSH_LOG="$LOG_DIR/dsh-web.log"

# ---------------------------------------------------------------------------
# 隔离：在派生任何子进程之前就把 home 钉在仓库内
# ---------------------------------------------------------------------------
export DSH_HOME="$RUNTIME_DIR/dsh-home"
export DSH_AGENTS_HOME="$RUNTIME_DIR/agents-home"
mkdir -p "$DSH_HOME" "$DSH_AGENTS_HOME" "$LOG_DIR"

# 端口占用检查：有输出表示端口已被别的进程占用
port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    [[ -n "$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null)" ]]
  else
    # 没有 lsof 时退化为 node 探测（argv[1] = 端口，argv[2] = 探测主机）
    node -e '
      const net = require("node:net")
      const socket = net.connect({ port: Number(process.argv[1]), host: process.argv[2] })
      socket.setTimeout(800)
      socket.on("connect", () => { socket.destroy(); process.exit(0) })
      socket.on("timeout", () => { socket.destroy(); process.exit(1) })
      socket.on("error", () => process.exit(1))
    ' "$port" "$DSH_HOST"
  fi
}

# ---------------------------------------------------------------------------
# 0. 前置检查
# ---------------------------------------------------------------------------
command -v node >/dev/null 2>&1 || { err "未找到 node，请先安装 Node.js ≥ 24.9。"; exit 1; }
[[ -f "$DSH_BIN" ]] || {
  err "未找到自带 dsh：$DSH_BIN"
  err "请先执行一次性准备：./scripts/setup-runtime.sh （详见 docs/runtime.md）"
  exit 1
}
[[ -f "$PROFILE_DIR/package.json" ]] || {
  err "未找到本项目 profile：$PROFILE_DIR/package.json"
  err "请先执行：./scripts/setup-runtime.sh"
  exit 1
}
if [[ ! -f "$PROFILE_DIR/cordis.patch.yml" ]]; then
  warn "profile 缺少 cordis.patch.yml（dsh 首次启动会自动补一份空覆盖层，可以继续）"
fi

if port_in_use "$DSH_PORT"; then
  err "端口 $DSH_PORT 已被占用（dsh web 需要它）。"
  err "请换端口重试，例如：FDE_DSH_PORT=3081 ./scripts/start-all.sh"
  err "或先停掉占用进程（查看：lsof -nP -iTCP:$DSH_PORT -sTCP:LISTEN）。"
  exit 1
fi
if [[ "${FDE_NO_BACKEND:-0}" != "1" ]] && port_in_use "$BACKEND_PORT"; then
  err "端口 $BACKEND_PORT 已被占用（Java 后端需要它）。"
  err "请换端口重试，例如：FDE_SERVER_PORT=8082 ./scripts/start-all.sh"
  exit 1
fi

# ---------------------------------------------------------------------------
# 1. 进程管理与退出
# ---------------------------------------------------------------------------
DSH_PID=""
BACKEND_PID=""
SHUTTING_DOWN=0

kill_tree() {
  # 先温和（SIGTERM），5 秒后仍在则 SIGKILL。
  # 注意：macOS 自带 bash 3.2 的 kill 内建**不接受 -- 选项结束符**（kill -0 -- -123 会报错退出），
  # 所以这里直接对 PID 发信号，并先确认它还存在（对已退出的 PID 发组信号会误伤本进程）。
  local pid
  pid="$1"
  [[ -n "$pid" ]] || return 0
  kill -TERM "$pid" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.5
  done
  kill -KILL "$pid" 2>/dev/null || true
}

shutdown() {
  [[ "$SHUTTING_DOWN" -eq 0 ]] || return 0
  SHUTTING_DOWN=1
  echo ""
  info "正在停止 dsh web 与 Java 后端 …"
  [[ -n "$DSH_PID" ]] && kill_tree "$DSH_PID"
  [[ -n "$BACKEND_PID" ]] && kill_tree "$BACKEND_PID"
  wait 2>/dev/null || true
  info "已全部停止。"
  exit "${1:-0}"
}
trap 'shutdown 0' INT TERM
trap 'shutdown 0' HUP

# ---------------------------------------------------------------------------
# 2. 启动 dsh web（当前面的进程无法用 setsid 时，仍可正常收尾）
# ---------------------------------------------------------------------------
start_dsh() {
  info "启动 dsh web：$DSH_HOST:${DSH_PORT}（profile = web，DSH_HOME = ${DSH_HOME}）"
  : > "$DSH_LOG"
  (
    cd "$DSH_OUT" || exit 1
    # --no-open：由启动脚本统一决定是否打开浏览器，避免重复开窗
    exec node "$DSH_BIN" web --host "$DSH_HOST" --port "$DSH_PORT" --no-open
  ) > "$DSH_LOG" 2>&1 &
  DSH_PID=$!
}

# 从 dsh 日志里抓带 token 的授权地址（每次启动都会重新生成，不能写死）
# 结果写入全局变量 DSH_URL；返回 0 表示成功。
# 注意：不要用 URL=$(wait_for_dsh_url) 这种写法 —— macOS 自带 bash 3.2 在「子 shell 里调用函数」时
# 会把位置参数丢掉（至少会让 $2 之类的引用触发 set -u 报错），所以这里用全局变量回传。
wait_for_dsh_url() {
  DSH_URL=""
  local tries i line
  tries=90
  i=0
  while [[ "$i" -lt "$tries" ]]; do
    i=$((i + 1))
    line="$(grep -oE '^dsh web: https?://[^ ]+' "$DSH_LOG" 2>/dev/null | tail -n 1 | sed 's/^dsh web: //')"
    if [[ -n "$line" ]]; then
      DSH_URL="$line"
      return 0
    fi
    # dsh 进程提前退出：把日志尾部打出来供排查
    if ! kill -0 "$DSH_PID" 2>/dev/null; then
      return 1
    fi
    sleep 0.5
  done
  return 1
}

# 轮询 HTTP 状态码：参数 1 是地址，参数 2 是尝试次数。
# 成功（拿到任意非 000 的 HTTP 状态）返回 0，并把状态码写进全局变量 HTTP_RESULT。
wait_for_http() {
  local url tries code i
  url="$1"
  tries="$2"
  code=""
  i=0
  while [[ "$i" -lt "$tries" ]]; do
    i=$((i + 1))
    code="$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$url" 2>/dev/null)"
    if [[ -n "$code" ]] && [[ "$code" != "000" ]]; then
      HTTP_RESULT="$code"
      return 0
    fi
    sleep 0.5
  done
  HTTP_RESULT=""
  return 1
}

# ---------------------------------------------------------------------------
# 3. 启动 Java 后端（复用 start-backend.sh 的变量约定）
# ---------------------------------------------------------------------------
start_backend() {
  if [[ "${FDE_NO_BACKEND:-0}" == "1" ]]; then
    info "已按 FDE_NO_BACKEND=1 跳过 Java 后端"
    return 0
  fi
  # 密码可来自环境变量或 scripts/local.env（与 start-backend.sh 一致）
  if [[ -z "${FDE_DB_PASSWORD:-}" ]] && [[ -f "$REPO_ROOT/scripts/local.env" ]]; then
    # shellcheck disable=SC1091
    source "$REPO_ROOT/scripts/local.env"
  fi
  if [[ -z "${FDE_DB_PASSWORD:-}" ]]; then
    err "未设置 FDE_DB_PASSWORD，Java 后端无法连接数据库。"
    err "  export FDE_DB_PASSWORD='<密码>' ./scripts/start-all.sh"
    err "  或 cp scripts/local.env.example scripts/local.env 后填写（该文件已 gitignore）。"
    err "只想调试插件界面时可加 FDE_NO_BACKEND=1。"
    return 1
  fi
  export FDE_SERVER_PORT="$BACKEND_PORT"
  info "启动 Java 后端（端口 ${FDE_SERVER_PORT}，日志 ${BACKEND_LOG}）"
  (
    cd "$REPO_ROOT" || exit 1
    exec ./scripts/start-backend.sh
  ) > "$BACKEND_LOG" 2>&1 &
  BACKEND_PID=$!
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
echo "================================================================"
echo " Web 工作台 · 自带 dsh web + Java 后端"
echo "   dsh web   http://$DSH_HOST:$DSH_PORT      DSH_HOME=$DSH_HOME"
BACKEND_TITLE="http://127.0.0.1:$BACKEND_PORT"
[[ "${FDE_NO_BACKEND:-0}" == "1" ]] && BACKEND_TITLE="（已按 FDE_NO_BACKEND=1 跳过）"
echo "   Java 后端 $BACKEND_TITLE"
echo "================================================================"

start_dsh
if ! wait_for_dsh_url; then
  err "dsh web 启动失败，日志尾部（${DSH_LOG}）："
  tail -n 40 "$DSH_LOG" >&2 || true
  err "常见原因：profile 缺少 bundle（重跑 ./scripts/setup-runtime.sh）、端口被抢占、前端 dist 缺失。"
  shutdown 1
fi
AUTH_URL="$DSH_URL"

# 无 token 访问应当得到 401（说明服务已就绪且鉴权生效），用于确认端口真的在监听
if ! wait_for_http "http://$DSH_HOST:$DSH_PORT/" 40; then
  err "dsh web 已打印地址但端口无响应，日志尾部（${DSH_LOG}）："
  tail -n 40 "$DSH_LOG" >&2 || true
  shutdown 1
fi
HTTP_CODE="$HTTP_RESULT"

start_backend || shutdown 1
BACKEND_STATE="（已跳过）"
if [[ -n "$BACKEND_PID" ]]; then
  if ! wait_for_http "http://127.0.0.1:$BACKEND_PORT/api/health" 240; then
    err "Java 后端 120 秒内未就绪，日志尾部（${BACKEND_LOG}）："
    tail -n 40 "$BACKEND_LOG" >&2 || true
    err "常见原因：FDE_DB_PASSWORD 错误或数据库不可达（此时 /api/health 会返回 db=down，但服务应能起来）。"
    shutdown 1
  fi
  HEALTH="$(curl -s -m 3 "http://127.0.0.1:$BACKEND_PORT/api/health" 2>/dev/null || true)"
  BACKEND_STATE="HTTP $HTTP_RESULT  $HEALTH"
fi

# 干净地址（不带 token）访问 dsh web：未授权时返回 401，说明服务在监听
echo ""
echo "================================================================"
echo " 启动完成"
echo "---------------------------------------------------------------"
echo " dsh web 进程 PID   $DSH_PID   （端口响应 HTTP ${HTTP_CODE}，无 token 访问返回 401 属正常）"
echo " 后端      PID      ${BACKEND_PID:-（未启动）}"
echo " 后端健康检查       $BACKEND_STATE"
echo "---------------------------------------------------------------"
echo " 浏览器首次访问请用下面的授权地址（带一次性 token，每次启动都不同）："
echo ""
echo "   $AUTH_URL"
echo ""
echo " 打开后会种下 cookie 并跳到：http://$DSH_HOST:$DSH_PORT/"
echo " 之后直接访问该地址即可（同一浏览器）。"
echo "---------------------------------------------------------------"
echo " 日志：$DSH_LOG"
echo "       $BACKEND_LOG"
echo " 停止：Ctrl+C（会同时停掉两个进程）"
echo "================================================================"

if [[ "$WAIT_ONLY" -eq 1 ]]; then
  # --wait：把就绪信息交给调用方（供自动化/上层脚本使用），本进程随即退出，不托管子进程
  echo ""
  echo "READY dsh=$DSH_PID backend=${BACKEND_PID:-none} url=$AUTH_URL"
  exit 0
fi

if [[ "${FDE_DSH_OPEN:-0}" == "1" ]]; then
  if command -v open >/dev/null 2>&1; then open "$AUTH_URL" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$AUTH_URL" >/dev/null 2>&1 || true
  fi
fi

# ---------------------------------------------------------------------------
# 4. 守护：任一进程退出即整体停止（避免只活着一个进程时用户误判）
# ---------------------------------------------------------------------------
while true; do
  if ! kill -0 "$DSH_PID" 2>/dev/null; then
    err "dsh web 进程已退出，日志尾部（${DSH_LOG}）："
    tail -n 20 "$DSH_LOG" >&2 || true
    shutdown 1
  fi
  if [[ -n "$BACKEND_PID" ]] && ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    err "Java 后端进程已退出，日志尾部（${BACKEND_LOG}）："
    tail -n 20 "$BACKEND_LOG" >&2 || true
    shutdown 1
  fi
  sleep 1
done
