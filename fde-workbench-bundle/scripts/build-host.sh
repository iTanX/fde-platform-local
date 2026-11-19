#!/usr/bin/env bash
# 只构建 Host 半（lib/types/host/** → lib/index.js），不碰 Client bundle。
#
# 为什么单独有这个脚本：`scripts/build.sh` 的第 3 步（tsdown）会把 Host 与 Client
# 两个产物一起打包——只要任何一个失败，两个产物都不会更新。并行开发时
# （T5-3 在改 src/client/），Host 半的验证不该被 Client 的中间态挡住。
#
# 用法：
#   DSH_SRC=<DSH 源码 checkout> bash scripts/build-host.sh
#   默认 DSH_SRC=../runtime/src（本仓库自带的 dsh 源码）
#
# 产物：lib/index.js（Host 行 `fde-workbench` 加载的那份）+ lib/types/host/**
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DSH_SRC="${DSH_SRC:-$HERE/../runtime/src}"
TSC="$DSH_SRC/node_modules/typescript/bin/tsc"

if [ ! -d "$DSH_SRC/node_modules" ] || [ ! -f "$TSC" ]; then
  echo "build-host: DSH_SRC=$DSH_SRC 不是已安装依赖的 DSH 源码 checkout" >&2
  exit 1
fi

cd "$HERE"
echo "== 1/2 tsc host =="
node "$TSC" -p tsconfig.host.json > /tmp/fde-workbench-tsc-host.log 2>&1 || true
HOST_ERRORS="$(grep -E '^src/.*error TS' /tmp/fde-workbench-tsc-host.log | grep -v 'TS6059' || true)"
if [ -n "$HOST_ERRORS" ]; then
  echo "$HOST_ERRORS"
  echo "build-host: host 类型检查失败（本包源码有错误）" >&2
  exit 1
fi
echo "host 类型检查：本包源码无错误（依赖包诊断已忽略 $(grep -c 'error TS' /tmp/fde-workbench-tsc-host.log) 条）"
rm -f /tmp/fde-workbench-tsc-host.log

echo "== 2/2 tsdown（只产 Host 产物）=="
# 用 tsdown.host.config.ts：它从官方 clientBundle 预设里只取「Node 半」那一条配置，
# 因此 src/client/ 处于中间态时 Host 产物仍能独立产出并验证（见该文件的注释）。
# cwd 保持插件根目录：tsdown 的 entry / outDir 都以 cwd 为基准（该配置文件开头会再 chdir 一次保证）。
cd "$HERE"
DSH_SRC="$DSH_SRC" "$DSH_SRC/node_modules/.bin/tsdown" --config-loader native --config "$HERE/tsdown.host.config.ts"

echo "== done =="
ls -la "$HERE/lib/index.js"
