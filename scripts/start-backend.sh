#!/usr/bin/env bash
# =============================================================================
# Web 工作台 Java 后端启动脚本（本地开发用）
#
# 用法：
#   export FDE_DB_PASSWORD='你的数据库密码'   # 或写进 scripts/local.env（不入库）
#   ./scripts/start-backend.sh
#
# 说明：
#   1. 脚本本身不含任何密码；密码只从环境变量或 scripts/local.env 读取。
#   2. 默认连接 192.168.2.125:3306 的 fde_workbench 库（已建好，19 张表）。
#   3. 端口默认 8081，可用 FDE_SERVER_PORT 覆盖。
#   4. Windows 请用 Git Bash 或 WSL 执行。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/workbench"
LOCAL_ENV="$REPO_ROOT/scripts/local.env"

# 本地环境变量（可选，已在 .gitignore 中排除）
if [[ -f "$LOCAL_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$LOCAL_ENV"
fi

if [[ -z "${FDE_DB_PASSWORD:-}" ]]; then
  echo "[错误] 未设置 FDE_DB_PASSWORD。" >&2
  echo "       请先 export FDE_DB_PASSWORD='...'，或复制 scripts/local.env.example 为 scripts/local.env 后填写。" >&2
  exit 1
fi

export FDE_SERVER_PORT="${FDE_SERVER_PORT:-8081}"
export FDE_DB_URL="${FDE_DB_URL:-jdbc:mysql://192.168.2.125:3306/fde_workbench?sessionVariables=time_zone='%2B08:00'&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true}"
export FDE_DB_USERNAME="${FDE_DB_USERNAME:-root}"

if [[ ! -d "$BACKEND_DIR" ]]; then
  echo "[错误] 未找到后端目录：$BACKEND_DIR" >&2
  echo "       T0-1（Java 多模块骨架）可能尚未完成。" >&2
  exit 1
fi

JAR="$(ls -1 "$BACKEND_DIR"/workbench-app/target/workbench-app-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)"
if [[ -z "$JAR" ]]; then
  echo "[信息] 未找到构建产物，先执行打包..."
  (cd "$BACKEND_DIR" && mvn -q -DskipTests package)
  JAR="$(ls -1 "$BACKEND_DIR"/workbench-app/target/workbench-app-*.jar | grep -v '\.original$' | head -n 1)"
fi

echo "[信息] 启动后端：$JAR"
echo "[信息] 监听端口：$FDE_SERVER_PORT"
echo "[信息] 数据库：${FDE_DB_USERNAME}@${FDE_DB_URL%%\?*}"

exec java -jar "$JAR" \
  --server.port="$FDE_SERVER_PORT" \
  --spring.datasource.url="$FDE_DB_URL" \
  --spring.datasource.username="$FDE_DB_USERNAME" \
  --spring.datasource.password="$FDE_DB_PASSWORD"
