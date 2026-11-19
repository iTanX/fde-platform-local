/**
 * Web 工作台插件 —— Host 半（Node 进程内）。
 *
 * 本阶段（T5-2）在 Host 侧提供三样东西（docs/plugin-integration.md §5）：
 *   1. 四个模型工具（`node_context_get` / `node_reference_read` / `node_output_write` /
 *      `node_output_commit`），让模型能"读上下文 → 读引用 → 写输出 → 提交登记 → 节点推进"；
 *   2. 内置指导技能 `workbench-node-delivery`（正文在 `skills/workbench-node-delivery/SKILL.md`）；
 *   3. 一行节点上下文 prompt 段，仅在会话 cwd 属于需求 workspace 时出现。
 *
 * 为什么 Host 不注册 HTTP 路由：Host 只做"模型侧"适配；业务数据、权限与文件归 Java，
 * 插件 Client 半直接调 `/api/**`（T5-3）。Host 的失败面因此只有"工具不可用"，
 * 不会影响页面。
 *
 * 失败策略：三块各自 try/catch 并打日志——令牌没配好、技能文件丢了这类问题
 * 不应该让整棵 DSH 插件树起不来（那会连带用户的所有会话）。
 */

import type { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import type {} from '@deepseek-ai/dsh-skill'
import type {} from '@deepseek-ai/dsh-system-prompt'
import type {} from '@deepseek-ai/dsh-tools'
import type {} from '@deepseek-ai/dsh-working-directory'
import { BackendClient } from './backend.ts'
import { NodePromptSections } from './prompt.ts'
import { loadBundledSkill, SKILL_NAME } from './skill.ts'
import { CONFIG_FIELDS, resolveSettings, validateConfig } from './settings.ts'
import type { Config, Settings } from './settings.ts'
import { registerTools, TOOL_NAMES } from './tools.ts'
import { nodeSkillProvider } from './node-skills.ts'
import { NodeDirectory } from './workbench.ts'

export { CONFIG_FIELDS } from './settings.ts'
export type { Config } from './settings.ts'
export { loadBundledSkill, SKILL_NAME } from './skill.ts'
export { renderNodePromptLine, PROMPT_SECTION_NAME, PROMPT_SECTION_ORDER, NodePromptSections } from './prompt.ts'
export { TOOL_NAMES } from './tools.ts'
export { NodeDirectory } from './workbench.ts'
export { BackendClient } from './backend.ts'
export { resolveSettings } from './settings.ts'

/** 已经完成注册的 loader 根上下文（用于 apply 幂等，见 apply 开头注释）。 */
const installedRoots = new WeakSet<object>()

/** 插件名称（Loader 与日志里出现的稳定标识）。 */
export const name = '@local/dsh-workbench-bundle'

/** 本插件依赖的服务（DSH Loader 会等它们就绪后再调用 apply）。 */
export const inject = ['tools', 'skills', 'systemPrompt', 'agents', 'workingDirectory']

/**
 * Host 插件主体。
 * @param ctx - 插件上下文
 * @param config - 插件配置（字段见 {@link CONFIG_FIELDS}；此处做一次显式校验）
 */
export function apply(ctx: Context, config: Config = {}): void {
  // 幂等：cordis.patch.yml 里的 Host 行与 Client 行都指向本包，两行都会调用 apply。
  // 只注册一份（否则同名工具被重复注册、prompt 段重复求值）。
  // 判定依据是"同一个 loader 根上下文"：用 ctx.root 做键，两行共享同一个根。
  // ctx.root 在真实 Cordis 里一定存在；替身/极简上下文可能没有，没有就不做幂等判断
  const root = (ctx as { root?: object }).root
  if (root !== undefined) {
    if (installedRoots.has(root)) return
    installedRoots.add(root)
  }
  // 环境变量和插件配置都未提供 storageRoot 时，从进程 cwd（如 `<storageRoot>/ws/<id>`）推导。
  validateConfig(config)
  const settings: Settings = resolveSettings(config, defaultStorageRoot())
  if (settings.internalToken.length === 0) {
    // 只提醒一次：没有令牌时后端一律 401，工具会给可读错误，但根因在配置
    ctx.logger.warn('workbench: 未配置 internalToken（后端 FDE_INTERNAL_TOKEN），/internal/** 调用会被拒绝')
  }
  const client = new BackendClient({ javaBaseUrl: settings.javaBaseUrl, internalToken: settings.internalToken })
  const directory = new NodeDirectory(client, settings)

  // ---- 1. 工具 ----
  try {
    registerTools(ctx, { client, settings, directory })
    ctx.logger.info(`workbench: 已注册工具 ${TOOL_NAMES.join('、')}`)
  } catch (error) {
    ctx.logger.warn(`workbench: 工具注册失败：${errorText(error)}`)
  }

  // ---- 2. 内置技能 ----
  try {
    const skill = loadBundledSkill()
    ctx.skills.register(skill)
    ctx.logger.info(`workbench: 已注册内置技能 ${skill.name}（${skill.content.length} 字符）`)
  } catch (error) {
    ctx.logger.warn(`workbench: 内置技能 ${SKILL_NAME} 注册失败：${errorText(error)}`)
  }

  // ---- 3. 节点自定义技能 ----
  // 节点上配置的技能（可多个，按配置顺序）以「快照版本」提供给模型：目录只给元数据，
  // 正文由 DSH 的 skill 工具首次调用时经 `/internal/.../skills/{position}` 读取，
  // 因此不会把其他项目的技能暴露给本会话，也不把正文塞进 prompt。
  // provider 注册在插件全局层：每次 list 都按 cwd 现算当前节点，节点推进后自动跟着变。
  try {
    ctx.skills.registerProvider(() => ({
      name: 'workbench-node-skills',
      async list({ cwd, signal }) {
        // 非需求 workspace（或解析不出当前节点）时返回空：普通会话不该看到节点技能
        const context = await directory.resolveNodeByCwd(cwd, signal)
        if (context === undefined) return []
        const delegates = nodeSkillProvider(client, context)
        return delegates.list({ cwd, signal })
      },
      async get(candidate, { cwd, signal }) {
        const context = await directory.resolveNodeByCwd(cwd, signal)
        if (context === undefined) return undefined
        if (candidate.provider !== 'workbench-node-skills') return undefined
        return nodeSkillProvider(client, context).get(candidate, { cwd, signal })
      },
    }))
    ctx.logger.info('workbench: 已注册节点技能提供者（按节点快照版本）')
  } catch (error) {
    ctx.logger.warn(`workbench: 节点技能提供者注册失败：${errorText(error)}`)
  }

  // ---- 4. 节点上下文 prompt 段 ----
  // 文本按会话 cwd 求值：属于需求 workspace 时给一行指令，否则空串（等于不出现）。
  // 需要的节点名 / seq / total / 技能名只在 `/context` 里有，所以先同步解析 cwd（0 次网络），
  // 属于需求 workspace 才去问后端；普通会话一次网络请求都不发。
  const sections = new NodePromptSections(ctx, async (requirementId, signal) => {
    const context = await directory.resolveNodeByRequirement(requirementId, signal)
    if (context === undefined) return undefined
    return {
      requirementTitle: context.requirementTitle,
      nodeSeq: context.nodeSeq,
      nodeCount: context.nodeCount,
      nodeName: context.nodeName,
      skillNames: context.skills.map(skill => (skill.versionNo === undefined || skill.versionNo === null
        ? skill.name
        : `${skill.name}(v${skill.versionNo})`)),
      outputDir: context.outputDir,
    }
  })

  const syncAgent = (agent: Agent): void => {
    // 同步解析 cwd：prompt 段的 text 回调必须是同步的，这里提前把会话 cwd 放好
    void (async () => {
      const cwd = await directory.cwdOf(agent)
      await sections.sync(agent, cwd, settings.storageRoot)
    })().catch((error: unknown) => {
      ctx.logger.warn(`workbench: prompt 段同步失败：${errorText(error)}`)
    })
  }

  ctx.effect(() => {
    // 会话创建时同步一次；每轮开始前再同步一次（用户可能 cd 过，或节点刚推进）；
    // 会话回收时忘掉它的 scope。三处都用官方事件。
    const offCreated = ctx.on('agent/created', ({ agent }) => { syncAgent(agent) })
    const offStep = ctx.on('agent/pre-step', ({ agent }, next) => {
      syncAgent(agent)
      return next()
    })
    const offDisposed = ctx.on('agent/disposed', ({ agent }) => { sections.forget(agent) })
    // 插件热重载时，已存在的会话也要拿到段
    for (const agent of ctx.agents.list()) syncAgent(agent)
    return () => {
      offCreated()
      offStep()
      offDisposed()
    }
  }, 'workbench.node-prompt')
}

/** 把异常转成一行文本（日志用）。 */
function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/**
 * 未配置 `storageRoot` 时按进程 cwd 推导：`<storageRoot>/ws/<id>` 的父父目录。
 * 解析不出这个形状时退回进程 cwd 本身（工具随后会给出"不属于需求 workspace"的可读错误）。
 * @returns 存储根绝对路径
 */
export function defaultStorageRoot(cwd: string = process.cwd()): string {
  const normalized = cwd.replace(/\\/g, '/').replace(/\/+$/, '')
  const match = /^(.*)\/ws\/\d+$/.exec(normalized)
  return match?.[1] !== undefined && match[1].length > 0 ? match[1] : normalized
}
