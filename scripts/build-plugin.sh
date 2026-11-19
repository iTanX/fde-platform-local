#!/usr/bin/env bash
# =============================================================================
# DSH 插件构建脚本（本地开发用）
#
# 用法：
#   ./scripts/build-plugin.sh
#
# 说明：
#   调用插件包自己的构建入口。setup 之后同步并重建已安装的插件副本。
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_SOURCE="$REPO_ROOT/fde-workbench-bundle"
RUNTIME_DIR="${FDE_RUNTIME_DIR:-$REPO_ROOT/runtime}"
INSTALLED_PLUGIN="$RUNTIME_DIR/workbench-bundle"

if [[ ! -f "$PLUGIN_SOURCE/package.json" ]]; then
  echo "[错误] 未找到插件目录：$PLUGIN_SOURCE" >&2
  exit 1
fi

PLUGIN_DIR="$PLUGIN_SOURCE"
if [[ -e "$INSTALLED_PLUGIN" || -L "$INSTALLED_PLUGIN" ]]; then
  if [[ ! -d "$INSTALLED_PLUGIN" || -L "$INSTALLED_PLUGIN" ]]; then
    echo "[错误] 插件安装目录必须是实际目录：$INSTALLED_PLUGIN" >&2
    exit 1
  fi
  PLUGIN_DIR="$(cd "$INSTALLED_PLUGIN" && pwd -P)"
  # 构建成功前保留现有 lib 子目录，并隔离开发时的 node_modules。
  rsync -a --delete --exclude=/.git --exclude=/node_modules --exclude=/lib \
    "$PLUGIN_SOURCE"/ "$PLUGIN_DIR"/
fi

DSH_SRC="$(cd "${DSH_SRC:-$RUNTIME_DIR/src}" && pwd -P)" || {
  echo "[错误] 未找到 DSH 源码，请先运行 ./scripts/setup-runtime.sh 或指定 DSH_SRC。" >&2
  exit 1
}
export DSH_SRC
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
