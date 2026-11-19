#!/usr/bin/env bash
# DSH_SRC 指向已安装构建依赖的 DSH 源码。默认使用本项目的 runtime/src。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DSH_SRC="${DSH_SRC:-$HERE/../runtime/src}"
if [[ ! -f "$DSH_SRC/node_modules/typescript/bin/tsc" || ! -x "$DSH_SRC/node_modules/.bin/tsdown" ]]; then
  echo "build: DSH_SRC=$DSH_SRC 缺少 DSH 构建依赖" >&2
  exit 1
fi
DSH_SRC="$(cd "$DSH_SRC" && pwd -P)"
export DSH_SRC

case "${1:-}" in
  '') faces=(host client) ;;
  host|client) faces=("$1") ;;
  *) echo "用法：bash scripts/build.sh [host|client]" >&2; exit 1 ;;
esac

# 在独立的包副本中生成中间产物，避免读取源码目录的 node_modules 或上次的 lib。
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/fde-workbench-build.XXXXXXXX")"
build_dir="$(cd "$build_dir" && pwd -P)"
trap 'rm -rf -- "${build_dir:?}"' EXIT
cp -R "$HERE/src" "$HERE/package.json" "$HERE"/tsconfig.{host,client}.json "$HERE"/tsdown*.config.ts "$build_dir/"

(
  cd "$build_dir"
  for face in "${faces[@]}"; do
    echo "== 构建 $face =="
    node "$HERE/scripts/compile-face.mjs" "$face"
    cfg=tsdown.config.ts
    if [[ "$face" == host ]]; then cfg=tsdown.host.config.ts; fi
    "$DSH_SRC/node_modules/.bin/tsdown" --config-loader native --config "$cfg"
  done
)

node "$HERE/scripts/verify-bundle.mjs" "$build_dir/lib" "${faces[@]}"
mkdir -p "$HERE/lib"
cp -R "$build_dir/lib/." "$HERE/lib/"
