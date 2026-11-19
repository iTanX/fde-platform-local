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
const DSH_SRC = process.env.DSH_SRC ?? '/tmp/dsh-plugin-dev'

/** 插件包目录（本文件的上一级）。 */
const PACKAGE_DIR = new URL('.', import.meta.url).pathname

const { clientBundle } = await import(`${DSH_SRC}/packages/client/tsdown.client.ts`)

// Host 入口：官方包的 tsconfig 会把 src/index.ts 编到 lib/types/index.js；
// 本包按任务卡把 Host 代码放在 src/host/，对应产物是 lib/types/host/index.js。
export default clientBundle(PACKAGE_DIR, ['lib/types/host/index.js'])
