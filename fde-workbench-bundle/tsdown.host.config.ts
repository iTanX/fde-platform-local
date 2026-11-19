/**
 * 只产出 Host 半的 tsdown 配置（供 scripts/build-host.sh 使用）。
 *
 * 为什么不直接用 tsdown.config.ts：那会走官方 `clientBundle` 预设，把 Host 与 Client
 * 两个产物一起构建——只要 Client 处于中间态（并行开发时 T5-3 正在改 src/client/），
 * Host 产物也产不出来。这里单独从 `clientLibraryConfig` 那条路径取「Node 半」配置：
 * 产物、外部依赖规则与官方预设完全一致，只是不构建浏览器 bundle。
 *
 * 用法（由 build-host.sh 调用，cwd 必须是 DSH 源码 checkout）：
 *   DSH_SRC=<checkout> corepack pnpm exec tsdown --config-loader native --config <本文件>
 */

const DSH_SRC = process.env.DSH_SRC ?? new URL('../runtime/src', import.meta.url).pathname
const PACKAGE_DIR = new URL('.', import.meta.url).pathname
// tsdown 解析 entry / outDir 时以**进程 cwd** 为基准，而官方 clientBundle 的产物路径
// （lib/types/host/index.js）是相对插件包的，所以这里先把 cwd 切到插件根目录。
process.chdir(PACKAGE_DIR)

const { clientBundle } = await import(`${DSH_SRC}/packages/client/tsdown.client.ts`)

// clientBundle 的返回值是一个「按 face 选配置」的函数；不声明 face 时它会返回
// [Node 半, Client 半]，这里只留 platform=node 且入口是 lib/types/host/index.js 的那一条。
const configs = clientBundle(PACKAGE_DIR, ['lib/types/host/index.js'])({ env: {} })
const hostConfigs = Array.isArray(configs) ? configs : [configs]
// 先按 platform 筛，再按入口筛（Client 半的 entry 是对象形式，不是字符串数组，不参与这里）
const nodeHalf = hostConfigs
  .filter(config => config.platform === 'node')
  .filter(config => Array.isArray(config.entry) && config.entry.includes('lib/types/host/index.js'))
if (nodeHalf.length !== 1) {
  throw new Error(`build-host: 期望恰好一个 Node 半配置，实际 ${nodeHalf.length} 个（共 ${hostConfigs.length} 条）`)
}
/**
 * 裸包名判定：`@scope/name` 或 `name`（允许子路径），不匹配以 `.` 开头的相对模块。
 * @param specifier - 模块说明符
 */
function isBarePackage(specifier) {
  return /^(@[^/]+\/[^/]+|[a-z][^/.][^/]*)(\/|$)/i.test(specifier)
}

// Host 半的打包策略：**框架包一律外部**，只把 node: 内置与插件自己的相对模块打进单文件。
// 两个理由：
//   1. `@deepseek-ai/dsh-tools` 的 `defineTool` 与宿主 `ToolsService` 是同一份类型契约，
//      内联会出现"两个注册表 / 两个错误类"；
//   2. 更致命：内联时 rolldown 解析到的是 DSH 的 **src/*.ts**（插件的 paths 指向源码），
//      而 `dsh-llm` 的源码里有 `@Remote` 这类**装饰器**——官方 lib 已转译、源码没有，
//      内联会把装饰器带进我们的产物，运行期直接 SyntaxError（实测踩到过）。
// 外部包由 dsh 安装目录 / profile 的 node_modules 解析，与宿主插件是同一份实例。
export default nodeHalf.map(config => ({
  ...config,
  deps: {
    neverBundle: specifier => isBarePackage(specifier) || specifier.startsWith('node:'),
    alwaysBundle: () => false,
  },
}))
