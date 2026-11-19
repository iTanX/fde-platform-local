#!/usr/bin/env bash
# 构建 Web 工作台插件包：产出 Host 入口 lib/index.js 与浏览器 Client bundle lib/client.js。
#
# 采用 DSH 官方工具链（DSH 仓库 packages/client/tsdown.client.ts 的 clientBundle 预设），
# 与被 install_bundle 安装的 dsh-client-ui-* 包完全同一条产线：
#   1) tsc -p tsconfig.host.json     → lib/types/host/index.js
#   2) tsc -p tsconfig.client.json   → lib/types/client/**.js
#   3) tsdown --config tsdown.config.ts（包内配置，调用官方 clientBundle 预设）
#        → lib/index.js    （Node ESM，Host 行加载）
#        → lib/client.js   （浏览器 CJS 工厂：window.__ModuleLoader__.load({ id, factory })）
#
# 用法：
#   DSH_SRC=<已构建依赖包的 DSH 源码 checkout> bash scripts/build.sh
#   默认 DSH_SRC=../runtime/src（本仓库自带的可携带 dsh 源码；须先跑过 setup-runtime.sh）
# DSH_SRC 必须指向一份「已构建过依赖包的 DSH 源码 checkout」，准备方法见 README.md。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DSH_SRC="${DSH_SRC:-$HERE/../runtime/src}"
TSC="$DSH_SRC/node_modules/typescript/bin/tsc"

if [ ! -d "$DSH_SRC/node_modules" ] || [ ! -f "$TSC" ]; then
  echo "build: DSH_SRC=$DSH_SRC 不是已安装依赖的 DSH 源码 checkout" >&2
  exit 1
fi

cd "$HERE"
echo "== 1/3 tsc host =="
# T5-2 起 Host 半要 import DSH 官方包（cordis / dsh-tools / dsh-skill …），类型来自
# DSH 源码 checkout（见 tsconfig.host.json 的 extends + typeRoots）。与 client 半同理：
# 依赖包源码不在本包 rootDir 下（TS6059），这类诊断忽略，只把「本包源码」的当错误。
node "$TSC" -p tsconfig.host.json > /tmp/fde-workbench-tsc-host.log 2>&1 || true
HOST_ERRORS="$(grep -E '^src/.*error TS' /tmp/fde-workbench-tsc-host.log | grep -v 'TS6059' || true)"
if [ -n "$HOST_ERRORS" ]; then
  echo "$HOST_ERRORS"
  echo "build: host 类型检查失败（本包源码有错误）" >&2
  exit 1
fi
echo "host 类型检查：本包源码无错误（依赖包诊断已忽略 $(grep -c 'error TS' /tmp/fde-workbench-tsc-host.log) 条）"
rm -f /tmp/fde-workbench-tsc-host.log

echo "== 2/3 tsc client =="
# 本包不在 DSH workspace 内，依赖包的类型来自 DSH 源码（见 tsconfig.client.json）。
# tsc 会连依赖包源码一起分析，那些诊断来源于 DSH 自己的严格选项与类型工程上下文，
# 不属于本包的检查范围；这里只把「本包源码」的诊断当作错误（与 rootDir 类诊断一起忽略）。
node "$TSC" -p tsconfig.client.json > /tmp/fde-workbench-tsc-client.log 2>&1 || true
# TS6059 = 依赖包源码不在本包 rootDir 下（这种解析方式的必然结果，不是本包的错误）。
OWN_ERRORS="$(grep -E '^src/.*error TS' /tmp/fde-workbench-tsc-client.log | grep -v 'TS6059' || true)"
if [ -n "$OWN_ERRORS" ]; then
  echo "$OWN_ERRORS"
  echo "build: client 类型检查失败（本包源码有错误）" >&2
  exit 1
fi
echo "client 类型检查：本包源码无错误（依赖包诊断已忽略 $(grep -c 'error TS' /tmp/fde-workbench-tsc-client.log) 条）"
rm -f /tmp/fde-workbench-tsc-client.log

echo "== 3/3 tsdown =="
# Host 与 Client 对「@deepseek-ai/* 是否外部化」的要求相反，必须分两条命令产
# （理由分别写在 tsdown.config.ts 与 tsdown.host.config.ts 的文件头）：
#   1) Client：tsdown.config.ts（官方预设，宿主模块表外部化）→ lib/client.js
#   2) Host  ：tsdown.host.config.ts（框架包全部外部）→ lib/index.js
# **Host 必须最后执行**：Host 配置会把 cwd 切到插件根目录，若在 Client 之前跑，
# 官方预设会在错误的 cwd 下解析并产出空壳产物。
cd "$DSH_SRC"
DSH_SRC="$DSH_SRC" corepack pnpm exec tsdown --config-loader native --config "$HERE/tsdown.config.ts"
echo "== 3b 只产 Host 半（覆盖上面的 Node 半产物）=="
DSH_SRC="$DSH_SRC" bash "$HERE/scripts/build-host.sh" | sed -n '/tsdown/,$p'

echo "== done =="
ls -la "$HERE/lib"
