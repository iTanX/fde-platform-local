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
set +x
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/workbench"
# shellcheck source=scripts/start-env.sh
source "$REPO_ROOT/scripts/start-env.sh"
load_local_env
set_storage_root

if [[ -z "${FDE_DB_PASSWORD:-}" ]]; then
  echo "[错误] 未设置 FDE_DB_PASSWORD。" >&2
  echo "       请先 export FDE_DB_PASSWORD='...'，或复制 scripts/local.env.example 为 scripts/local.env 后填写。" >&2
  exit 1
fi

export FDE_SERVER_PORT="${FDE_SERVER_PORT:-8081}"

if [[ ! -d "$BACKEND_DIR" ]]; then
  echo "[错误] 未找到后端目录：$BACKEND_DIR" >&2
  echo "       T0-1（Java 多模块骨架）可能尚未完成。" >&2
  exit 1
fi

JAR="$(ls -1 "$BACKEND_DIR"/workbench-app/target/workbench-app-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)"
if [[ -z "$JAR" ]]; then
  echo "[信息] 未找到构建产物，先执行打包..."
  (
    cd "$BACKEND_DIR"
    unset FDE_DB_PASSWORD FDE_DB_URL FDE_DB_USERNAME FDE_INTERNAL_TOKEN FDE_JWT_SECRET \
      SPRING_DATASOURCE_PASSWORD SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME
    mvn -q -DskipTests package
  )
  JAR="$(ls -1 "$BACKEND_DIR"/workbench-app/target/workbench-app-*.jar | grep -v '\.original$' | head -n 1)"
fi

# Spring 从进程环境读取启动参数，数据库凭据不会进入 Java 的命令行。
export SERVER_PORT="$FDE_SERVER_PORT"
export FDE_DB_PASSWORD SPRING_DATASOURCE_PASSWORD="$FDE_DB_PASSWORD"
export FDE_INTERNAL_TOKEN FDE_JWT_SECRET FDE_WORKBENCH_ALLOWED_ORIGINS
if [[ -n "${FDE_DB_URL:-}" ]]; then export SPRING_DATASOURCE_URL="$FDE_DB_URL"; fi
if [[ -n "${FDE_DB_USERNAME:-}" ]]; then export SPRING_DATASOURCE_USERNAME="$FDE_DB_USERNAME"; fi

echo "[信息] 启动后端：$JAR"
echo "[信息] 监听端口：$FDE_SERVER_PORT"

cd "$REPO_ROOT"
exec java -jar "$JAR"
