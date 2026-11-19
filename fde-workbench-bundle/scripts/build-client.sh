#!/usr/bin/env bash
# 只构建 Client 半（src/client → lib/client.js 浏览器 bundle）。
#
# 为什么单独有这个脚本（与 build-host.sh 对称，见 tsdown.config.ts 的说明）：
# Host 半与 Client 半对「@deepseek-ai/* 是否外部化」的要求相反，不能共用一套外部依赖规则：
#   - Host 半（T5-2）：框架包必须 external（与宿主同一份实例），所以 neverBundle: /^[^.]/；
#   - Client 半（T5-3）：宿主模块表（PLATFORM_MODULES）只提供 react / react/jsx-runtime，
#     `@deepseek-ai/dsh-session/types` 这类纯类型包必须被内联或擦除，否则浏览器加载报 Module not found。
# 因此 tsdown.config.ts 只产 Client（clientBundle(pkg, false)），Host 由 scripts/build-host.sh 产。
#
# 用法：
#   DSH_SRC=<已构建依赖包的 DSH 源码 checkout> bash scripts/build-client.sh
#   默认 DSH_SRC=../runtime/src（本仓库自带的可携带 dsh 源码）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DSH_SRC="${DSH_SRC:-$HERE/../runtime/src}"
TSC="$DSH_SRC/node_modules/typescript/bin/tsc"

if [ ! -d "$DSH_SRC/node_modules" ] || [ ! -f "$TSC" ]; then
  echo "build-client: DSH_SRC=$DSH_SRC 不是已安装依赖的 DSH 源码 checkout" >&2
  exit 1
fi

cd "$HERE"
echo "== 1/2 tsc client =="
# 依赖包源码不在本包 rootDir 下（TS6059）属于这套解析方式的必然结果，只把本包源码的诊断当错误。
node "$TSC" -p tsconfig.client.json > /tmp/fde-workbench-tsc-client.log 2>&1 || true
OWN_ERRORS="$(grep -E '^src/.*error TS' /tmp/fde-workbench-tsc-client.log | grep -v 'TS6059' || true)"
if [ -n "$OWN_ERRORS" ]; then
  echo "$OWN_ERRORS"
  echo "build-client: client 类型检查失败（本包源码有错误）" >&2
  exit 1
fi
echo "client 类型检查：本包源码无错误（依赖包诊断已忽略 $(grep -c 'error TS' /tmp/fde-workbench-tsc-client.log) 条）"
rm -f /tmp/fde-workbench-tsc-client.log

echo "== 2/2 tsdown（只产 Client bundle）=="
# cwd 必须是 DSH 源码 checkout：官方 clientBundle 预设以仓库根解析 plugins / 模块表
cd "$DSH_SRC"
# --env.DSH_BUILD_FACE=client：官方预设据此只返回 Client 面配置（见 tsdown.config.ts 的说明）
DSH_SRC="$DSH_SRC" corepack pnpm exec tsdown --config-loader native --env.DSH_BUILD_FACE=client \
  --config "$HERE/tsdown.config.ts"

echo "== done =="
ls -la "$HERE/lib"
