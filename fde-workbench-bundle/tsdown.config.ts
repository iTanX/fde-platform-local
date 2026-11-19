import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const preset = pathToFileURL(join(process.env.DSH_SRC!, 'packages/client/tsdown.client.ts'))
const { clientBundle } = await import(preset.href)

// Client 由 DSH 官方预设打包为浏览器模块工厂。
export default clientBundle(import.meta.dirname, ['lib/types/host/index.js'], {
  hostPhase: true,
})({ env: { DSH_BUILD_FACE: 'client' } })
