/**
 * Web 工作台插件 —— Host 半（Node 进程内）。
 *
 * 本阶段（T0-3）只验证「插件包能被 Host 加载」这一件事，因此 `apply` 是空实现：
 * 工具、内置技能、prompt 段、HTTP 路由分别由后续的 T5-2 / T5-3 任务注册到
 * `ctx.tools`、`ctx.skills`、`ctx.systemPrompt`、`ctx.webServer`。
 *
 * 保留一个可被 Loader 定位的 `apply` 的意义：cordis.patch.yml 里的 Host 行
 * （`id: fde-workbench`）指向本包的根基名，Loader 会 import 本文件并调用 `apply`。
 * 一旦这里抛错，整棵插件树会启动失败，所以本阶段刻意什么都不做。
 */

/** 插件名称（Loader 与日志里出现的稳定标识）。 */
export const name = '@local/dsh-workbench-bundle'

/**
 * Host 插件主体：本阶段为空实现，仅为保证插件行可加载。
 */
export function apply(): void {
  // 阶段 0 无 Host 行为：HTTP 路由与模型工具在 T5-2 接入。
}
