import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const preset = pathToFileURL(join(process.env.DSH_SRC!, 'packages/client/tsdown.client.ts'))
const { clientBundle } = await import(preset.href)

// Host 框架包由运行环境解析，避免内联框架实现并共享宿主的模块实例。
export default clientBundle(import.meta.dirname, ['lib/types/host/index.js'], {
  hostPhase: true,
  lib: {
    deps: { neverBundle: /^[^./]/, alwaysBundle: () => false },
  },
})({ env: { DSH_BUILD_FACE: 'host' } })
