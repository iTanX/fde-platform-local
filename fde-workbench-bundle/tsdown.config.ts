/**
 * tsdown 配置：直接用 DSH 仓库的官方 preset 产 Host 入口与 Client bundle。
 *
 * `clientBundle()`（`packages/client/tsdown.client.ts`）会把两件事一起做掉：
 *   - lib/index.js：Host 半（ESM / node），入口是 tsc 产出的 `lib/types/index.js`；
 *   - lib/client.js：浏览器 Client bundle，CJS + 官方模块加载器包裹
 *     `window.__ModuleLoader__.load({ id, factory: (require) => … })`，并按
 *     `dsh.client` 声明把宿主模块表里的依赖全部 external。
 *
 * 本文件由 `scripts/build.sh` 通过 `tsdown --config <本文件>` 调用，
 * 此时 tsdown 的 cwd 是 DSH 源码 checkout（preset 依赖仓库根目录），
 * 所以这里用绝对路径 import 官方 preset。
 */

/** DSH 源码 checkout（脚本里与 DSH_SRC 保持一致）。 */
const DSH_SRC = process.env.DSH_SRC ?? new URL('../runtime/src', import.meta.url).pathname

/** 插件包目录（本文件的上一级）。 */
const PACKAGE_DIR = new URL('.', import.meta.url).pathname

const { clientBundle } = await import(`${DSH_SRC}/packages/client/tsdown.client.ts`)

// Host 入口：官方包的 tsconfig 会把 src/index.ts 编到 lib/types/index.js；
// 本包按任务卡把 Host 代码放在 src/host/，对应产物是 lib/types/host/index.js。
// 本文件**只**产浏览器 Client bundle（Host 由 scripts/build-host.sh 的 tsdown.host.config.ts 产）。
//
// 为什么不再顺带打 Host（2026-11-19 T5-3 实测结论）：
// 官方 `clientBundle(pkg, entry)` 的两个产物共用一套外部依赖规则（默认把
// `@deepseek-ai/*` 当 external）。T5-2 的 Host 半需要把框架包**外部化**（`dsh-tools` 等必须与
// 宿主同一份实例），于是它在 `tsdown.host.config.ts` 里声明了 `neverBundle: /^[^.]/`；
// 而这条规则一旦套到 Client 半，就会把 `@deepseek-ai/dsh-session/types` 这类**纯类型包**
// 留成运行期 import，浏览器立刻报 "Module not found"。
// 两个产物对「@deepseek-ai/* 如何打包」的要求相反，因此拆成两条命令：
//   - `scripts/build-client.sh`（= 本文件，entry=false）→ lib/client.js
//   - `scripts/build-host.sh`（= tsdown.host.config.ts）→ lib/index.js
// 注意：**两条必须以 Host 最后执行**。Host 配置会把 cwd 切到插件根目录，
// 若在 Client 之前跑，官方 preset 在错误的 cwd 下解析会产出空壳产物（宿主加载失败）。
// 用官方的「构建面（face）」开关只产 Client：`env.DSH_BUILD_FACE = 'client'` 且
// `hostPhase: true` 时，clientBundle 只返回 Client 两条配置（不返回 Node 半）——
// 这是官方自己的机制，不需要我们复刻它的外部依赖规则。
export default clientBundle(PACKAGE_DIR, ['lib/types/host/index.js'], { hostPhase: true })
