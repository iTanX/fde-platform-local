/**
 * 工作台文案字典的注册入口。
 *
 * 拆分原因：全工作台文案有 200 多条，放一个文件难以维护。
 * 这里只负责把 `zh` / `en` 两个字典组合交给 `index.ts` 注册进 DSH `ctx.locale`。
 * key 集合的权威来源仍然是 `zh.ts`（`WorkbenchLocaleKey = keyof typeof zh`），
 * `en.ts` 用 `Record<WorkbenchLocaleKey, string>` 保证两边 key 完全一致（缺一个就编译失败）。
 */
import { en } from './en.ts'
import { zh } from './zh.ts'

/** 字典命名空间。 */
export const NS = 'workbench'

/** 注册到 `ctx.locale` 的字典集合。 */
export const dicts = { zh, en }

export { zh, en }
export type { WorkbenchLocaleKey } from './zh.ts'
