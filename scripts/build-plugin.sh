#!/usr/bin/env bash
# =============================================================================
# DSH 插件构建脚本（本地开发用）
#
# 用法：
#   ./scripts/build-plugin.sh
#
# 说明：
#   1. 构建方式以 T0-3 任务实测结论为准；本脚本调用插件包内声明的构建命令。
#   2. 若插件包尚未创建，脚本给出明确提示而不是失败退出。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_DIR="$REPO_ROOT/fde-workbench-bundle"

if [[ ! -d "$PLUGIN_DIR" ]]; then
  echo "[错误] 未找到插件目录：$PLUGIN_DIR" >&2
  echo "       T0-3（插件包骨架）可能尚未完成。" >&2
  exit 1
fi

cd "$PLUGIN_DIR"

# 优先使用插件包自带的构建入口，其次回落到 npm scripts
if node -e "process.exit(require('./package.json').scripts?.build ? 0 : 1)" 2>/dev/null; then
  echo "[信息] 执行 npm run build"
  npm run build
elif node -e "process.exit(require('./package.json').scripts?.bundle ? 0 : 1)" 2>/dev/null; then
  echo "[信息] 执行 npm run bundle"
  npm run bundle
else
  echo "[错误] package.json 未声明 build/bundle 脚本，请在插件包 README 中补充构建命令。" >&2
  exit 1
fi

echo "[信息] 构建完成。安装方式见 fde-workbench-bundle/README.md"
