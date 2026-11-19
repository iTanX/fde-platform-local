/**
 * 工作台文案字典（兼容入口）。
 *
 * 字典本体已按区域拆分到 `locales/zh.ts`（权威）与 `locales/en.ts`，
 * 这里保留原本的导出名（`NS` / `zh` / `en` / `dicts` / `WorkbenchLocaleKey`），
 * 让既有引用（`index.ts`、`session/NodeSessionView.tsx`）无需改动。
 */
export { NS, zh, en, dicts } from './locales/index.ts'
export type { WorkbenchLocaleKey } from './locales/index.ts'
