/**
 * 节点上下文 prompt 段（docs/plugin-integration.md §5.5）。
 *
 * 设计意图：**prompt 段只放一行短指令**。详细规则在技能正文里（`workbench-node-delivery`），
 * 节点输入输出明细在 `node_context_get` 的返回里——prompt 里塞明细既有 token 成本，
 * 又有"拿到的是过期快照"的风险。
 *
 * 只有「会话 cwd 属于某个需求 workspace」时才出现。实现上的一个已核实约束：
 * `SystemPrompt.section` 的 `text(context)` 回调里**没有 cwd**（`AssembleContext` 只有
 * `scope` / `signal`），所以不能像写普通 section 那样"在回调里判断 cwd"。
 *
 * 做法：**注册进会话自己的 agent 上下文**（`agent.ctx`，官方文档写明
 * "Agent-scoped context; its contributions are agent-local, unwind on disposal"）。
 * 在那里注册的 prompt 段只对该会话生效，会话销毁时自动撤销，也不需要额外持有 scope：
 *
 *   1. 会话创建（或每轮开始）时按它的 cwd 解析出需求；
 *   2. 解析得到 → 在该会话上下文里注册一行指令；
 *   3. 解析不到 → 什么都不注册（等于不出现）。
 *
 * 这样不同会话互不污染：一个会话在需求 workspace、另一个在普通目录时，两者同时正确。
 */

import type { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import { parseWorkspaceCwd } from './paths.ts'
import { SKILL_NAME } from './skill.ts'

/** prompt 段名（DSH 里必须唯一，命名与官方 `tool:*` 段同风格但带插件前缀）。 */
export const PROMPT_SECTION_NAME = 'workbench:node-context'

/**
 * 段排序号。官方工具段的 order 由 `getSectionOrder('TOOL_*')` 给出、都在千位以下，
 * 本段属于"当前任务上下文"，放到它们之后（数值更大），保证工具描述先被读到。
 */
export const PROMPT_SECTION_ORDER = 900

/** 渲染一行指令所需的最小节点信息（由会话 cwd 反查得到）。 */
export interface NodeBrief {
  requirementTitle: string
  nodeSeq: number
  nodeCount: number
  nodeName: string
  skillNames: string[]
  outputDir: string
}

/**
 * 渲染**一行**节点上下文指令。
 *
 * 刻意保持单行短句：技能名只列名称（正文由模型按需用 `skill` 工具加载），
 * 输出目录用相对路径（模型 cwd 就是 workspace 根），明细一律指向 `node_context_get`。
 * @param brief - 节点最小信息
 * @returns 单行文本
 */
export function renderNodePromptLine(brief: NodeBrief): string {
  const skills = brief.skillNames.length > 0 ? brief.skillNames.join('、') : '无'
  return `当前处于需求《${brief.requirementTitle}》的节点 ${brief.nodeSeq}/${brief.nodeCount}「${brief.nodeName}」；`
    + `生效技能：${skills}；输出目录：${brief.outputDir}；`
    + `开始前先调用 node_context_get 获取输出项（流程见技能 ${SKILL_NAME}），`
    + '产出必须用 node_output_write 写入后立即 node_output_commit。'
}

/**
 * 判断 cwd 是否属于需求 workspace。
 * @param cwd - 会话 cwd
 * @param storageRoot - 存储根
 * @returns 需求 ID 或 undefined
 */
export function requirementIdOfCwd(cwd: string | undefined, storageRoot: string): number | undefined {
  return parseWorkspaceCwd(cwd, storageRoot)?.requirementId
}

/** 一个会话在 prompt 注册上的状态：撤销函数 + 当前注册的文本。 */
interface SessionSection {
  /** 注册时拿到的 disposer；换文本前先撤销旧的，避免同名段重复注册抛错。 */
  dispose: (() => void) | undefined
  /** 当前生效的文本；undefined 表示尚未注册（= 不出现）。 */
  text?: string
}

/**
 * 每条会话维护一个 prompt scope，并按 cwd 解析结果注册一行指令或空串。
 *
 * 生命周期：随插件 fibre 回收。会话被删除/回收时 scope 一并释放。
 * 更新策略：同一会话文本未变就不重注册（避免无谓的 `system-prompt/change`）。
 */
export class NodePromptSections {
  private readonly sessions = new Map<Agent, SessionSection>()

  private readonly ctx: Context

  /** 按需求 ID 求节点最小信息；拿不到时返回 undefined（此时该会话看到空串）。 */
  private readonly lookupBrief: (requirementId: number, signal?: AbortSignal) => Promise<NodeBrief | undefined>

  constructor(
    ctx: Context,
    lookupBrief: (requirementId: number, signal?: AbortSignal) => Promise<NodeBrief | undefined>,
  ) {
    this.ctx = ctx
    this.lookupBrief = lookupBrief
  }

  /**
   * 解析一个会话并更新它的 prompt 段。
   * @param agent - 会话对应的 agent
   * @param cwd - 会话当前工作目录
   * @param storageRoot - 存储根
   * @param signal - 取消信号
   * @returns 该会话最终生效的文本（空串表示不出现）
   */
  async sync(agent: Agent, cwd: string | undefined, storageRoot: string, signal?: AbortSignal): Promise<string> {
    const requirementId = requirementIdOfCwd(cwd, storageRoot)
    let text = ''
    if (requirementId !== undefined) {
      try {
        const brief = await this.lookupBrief(requirementId, signal)
        if (brief !== undefined) text = renderNodePromptLine(brief)
      } catch (error) {
        // 后端临时不可用不应拖垮会话：只是这一轮不显示节点段
        this.ctx.logger.warn(`workbench: 节点上下文 prompt 段求值失败（requirement ${requirementId}）：${String(error)}`)
      }
    }
    this.register(agent, text)
    return text
  }

  /**
   * 在会话 scope 里注册（或替换）文本。
   * @param agent - 会话
   * @param text - 要注册的文本；空串=不出现
   */
  private register(agent: Agent, text: string): void {
    let section = this.sessions.get(agent)
    if (section === undefined) {
      section = { dispose: undefined }
      this.sessions.set(agent, section)
    }
    if (section.text === text) return
    // 同一会话里同名段不能重复注册（官方注册表会抛错）：换文本先撤销旧的
    section.dispose?.()
    section.text = text
    if (text.length === 0) {
      section.dispose = undefined
      return
    }
    // 会话上下文（agent.ctx）是 scoped 上下文：服务访问必须经 `inject()` 声明依赖，
    // 否则 cordis 会抛 "cannot get property without inject"。inject 的回调里注册，
    // 注册落在该会话的 scope 层；返回的 disposer 由这里保存，换文本/会话回收时撤销。
    let dispose: (() => void) | undefined
    agent.ctx.inject(['systemPrompt'], (scoped) => {
      dispose = scoped.systemPrompt.section({
        name: PROMPT_SECTION_NAME,
        order: PROMPT_SECTION_ORDER,
        text,
        // 需求名里出现 `${...}` 时不该被当成 prompt 变量插值
        interpolate: false,
      })
    })
    section.dispose = () => { dispose?.() }
  }

  /**
   * 忘记一个会话（会话被回收时调用）。
   * @param agent - 会话
   */
  forget(agent: Agent): void {
    const section = this.sessions.get(agent)
    if (section === undefined) return
    this.sessions.delete(agent)
    section.dispose?.()
  }

  /**
   * 当前登记的文本（验证脚本用来断言"属于/不属于需求 workspace"两种 cwd）。
   * @param agent - 会话
   * @returns 文本，未登记时为 undefined
   */
  textOf(agent: Agent): string | undefined {
    return this.sessions.get(agent)?.text
  }
}
