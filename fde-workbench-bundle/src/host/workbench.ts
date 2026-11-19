/**
 * 节点目录：把「会话 → 需求 → 当前节点」的解析集中起来，避免四个工具各写一遍。
 *
 * 为什么需要它：工具入参里的 `nodeInstanceId` 是可选的，而
 * `GET /internal/nodes/{nid}/context` 只认节点实例 ID——"当前节点是哪个"必须有人记住。
 * 记住的方式：**会话(agent) + cwd → requirementId → 节点上下文（缓存）**。
 *
 * 当前节点的三个来源（按优先级）：
 *   1. 模型显式给的 `nodeInstanceId`（最可信，多会话并行时唯一安全）；
 *   2. 该需求的缓存（上一次 `/context` 的结果；节点推进后由工具调用 `invalidate` 失效）；
 *   3. `<ws>/.workbench/<nid>/` ——模型输出目录，只有"有人在这个节点上工作过"才会存在，
 *      是当前节点最可靠的本地证据。候选里取"正在办理"的那个。
 *
 * 一个需求一个 workspace（docs/plugin-integration.md §5.1），节点在需求内串行，
 * 所以缓存不会把两个节点搞混；真要跨节点操作，显式传 `nodeInstanceId` 即可。
 */

import { readdir } from 'node:fs/promises'
import type { Agent } from '@deepseek-ai/dsh-agent'
import type {} from '@deepseek-ai/dsh-system-prompt'
import type {} from '@deepseek-ai/dsh-working-directory'
import { BackendClient } from './backend.ts'
import type { NodeContext } from './backend.ts'
import { MODEL_DIR, WS_DIR, parseWorkspaceCwd } from './paths.ts'
import type { Settings } from './settings.ts'

/**
 * 节点目录：需求 → 当前节点上下文。
 */
export class NodeDirectory {
  private readonly cache = new Map<number, NodeContext>()

  private readonly client: BackendClient

  private readonly settings: Settings

  constructor(client: BackendClient, settings: Settings) {
    this.client = client
    this.settings = settings
  }

  /**
   * 会话的 cwd：用 DSH 的 workingDirectory（与原生 read/write 工具同一来源）；
   * 拿不到时退回会话创建时的 `header.cwd`。
   * @param agent - 会话
   * @param signal - 取消信号
   * @returns 绝对路径或 undefined
   */
  async cwdOf(agent: Agent, signal?: AbortSignal): Promise<string | undefined> {
    try {
      return await agent.ctx.workingDirectory.ensure(agent, signal)
    } catch {
      return agent.session.header.cwd
    }
  }

  /**
   * 会话所在需求的 ID。
   * @param agent - 会话
   * @param signal - 取消信号
   * @returns 需求 ID；不属于需求 workspace 时 undefined
   */
  async requirementOf(agent: Agent, signal?: AbortSignal): Promise<number | undefined> {
    return parseWorkspaceCwd(await this.cwdOf(agent, signal), this.settings.storageRoot)?.requirementId
  }

  /**
   * 解析要操作的节点上下文（工具入口）。
   * @param agent - 当前会话
   * @param nodeInstanceId - 模型显式给出的节点（可空）
   * @param signal - 取消信号
   * @returns 节点上下文
   * @throws Error 中文可读错误
   */
  async resolveNode(agent: Agent, nodeInstanceId: number | undefined, signal?: AbortSignal): Promise<NodeContext> {
    const cwd = await this.cwdOf(agent, signal)
    const requirementId = parseWorkspaceCwd(cwd, this.settings.storageRoot)?.requirementId
    if (requirementId === undefined) {
      throw new Error(
        '当前会话的工作目录不属于任何需求 workspace，无法使用节点工具。'
        + `会话工作目录应为 <存储根>/ws/<需求ID>（当前：${cwd ?? '未知'}，配置的存储根：${this.settings.storageRoot}）。`
        + '请在工作台的节点页里打开该节点的会话。',
      )
    }
    if (nodeInstanceId !== undefined) {
      // 缓存命中就别再打后端：同一个节点在同一轮里会被反复用到
      const cached = this.cache.get(requirementId)
      if (cached?.nodeInstanceId === nodeInstanceId) return cached
      const context = await this.client.nodeContext(nodeInstanceId, signal)
      this.assertSameRequirement(context, requirementId)
      this.remember(context)
      return context
    }
    const cached = this.cache.get(requirementId)
    if (cached !== undefined) return cached
    const discovered = await this.discoverCurrentNode(requirementId, cwd ?? resolveWorkspace(this.settings.storageRoot, requirementId), signal)
    this.remember(discovered)
    return discovered
  }

  /**
   * 按需求 ID 求当前节点（prompt 段用：那里只有需求 ID，没有会话 cwd）。
   *
   * 与 `resolveNode` 的差别：找不到时返回 undefined 而不是抛错——prompt 段宁可不出现，
   * 也不能因为一次解析失败把会话的提示组装弄挂。
   * @param requirementId - 需求 ID
   * @param signal - 取消信号
   * @returns 节点上下文或 undefined
   */
  async resolveNodeByRequirement(requirementId: number, signal?: AbortSignal): Promise<NodeContext | undefined> {
    const cached = this.cache.get(requirementId)
    if (cached !== undefined) return cached
    const workspace = resolveWorkspace(this.settings.storageRoot, requirementId)
    const discovered = await this.discoverCurrentNode(requirementId, workspace, signal).catch(() => undefined)
    if (discovered !== undefined) this.remember(discovered)
    return discovered
  }

  /**
   * 失效某需求的缓存（节点推进后调用）。
   * @param requirementId - 需求 ID
   */
  invalidate(requirementId: number): void {
    this.cache.delete(Number(requirementId))
  }

  /**
   * 用 `/context` 的结果填缓存。
   * @param context - 后端返回的节点上下文
   */
  remember(context: NodeContext): void {
    this.cache.set(Number(context.requirementId), context)
  }

  /**
   * 没有 nodeInstanceId 时推断"当前节点"。
   *
   * 后端 `/context` 只接受节点实例 ID，没有"按需求查节点"的内部接口，
   * 因此这里用两条线索拼接（都不需要模型猜）：
   *   1. 本地的 `.workbench/<nid>/`（模型输出目录）：模型写过东西就一定有，最可靠；
   *   2. 若本地还没有：**探测节点实例 ID**——同一需求创建的节点 ID 相连，
   *      从需求 ID 起找一段窗口，逐个问 `/context`，只要返回的 `requirementId` 就是本需求就收下。
   * 候选里优先返回"正在办理（current）"的那个；都确认不了就抛可读错误，让模型显式传 ID。
   * @param requirementId - 需求 ID
   * @param workspace - workspace 绝对路径（cwd 或按存储根拼出）
   * @param signal - 取消信号
   * @returns 节点上下文
   * @throws Error 中文可读错误
   */
  private async discoverCurrentNode(requirementId: number, workspace: string, signal?: AbortSignal): Promise<NodeContext> {
    const modelDir = `${workspace.replace(/[\\/]+$/, '')}/${MODEL_DIR}`
    const entries = await readdir(modelDir, { withFileTypes: true }).catch(() => [])
    const local = modelDirNames(entries.filter(entry => entry.isDirectory()).map(entry => entry.name))
    const checked: string[] = []
    const confirmed: NodeContext[] = []
    for (const id of local) {
      const context = await this.tryContext(id, requirementId, signal)
      if (context === undefined) continue
      checked.push(`#${id}「${context.nodeName}」${context.nodeStatus}`)
      if (context.nodeStatus === 'current') return context
      confirmed.push(context)
    }
    // 本地还没有输出目录（新需求/新节点）→ 探测节点实例 ID
    for (const id of probeWindow(requirementId)) {
      if (local.includes(id)) continue
      const context = await this.tryContext(id, requirementId, signal)
      if (context === undefined) continue
      checked.push(`#${id}「${context.nodeName}」${context.nodeStatus}`)
      if (context.nodeStatus === 'current') return context
      confirmed.push(context)
    }
    if (confirmed.length > 0) return confirmed[0] as NodeContext
    throw new Error(
      `无法确定需求 ${requirementId} 的当前节点。`
      + (checked.length > 0 ? `已检查的节点：${checked.join('、')}。` : '该需求下没有找到可用的节点。')
      + '请在调用时给出 nodeInstanceId（工作台节点页可见）。',
    )
  }

  /**
   * 问一次 `/context`：属于本需求就返回上下文，否则 undefined（节点不存在/属于别的需求）。
   * @param nodeInstanceId - 节点实例 ID
   * @param requirementId - 期望的需求 ID
   * @param signal - 取消信号
   */
  private async tryContext(nodeInstanceId: number, requirementId: number, signal?: AbortSignal): Promise<NodeContext | undefined> {
    try {
      const context = await this.client.nodeContext(nodeInstanceId, signal)
      return Number(context.requirementId) === Number(requirementId) ? context : undefined
    } catch {
      // 不存在 / 库被复位过：跳过
      return undefined
    }
  }

  /** 后端说这个节点属于别的需求：明确报错，避免写错需求。 */
  private assertSameRequirement(context: NodeContext, requirementId: number): void {
    if (Number(context.requirementId) !== Number(requirementId)) {
      throw new Error(
        `节点 ${context.nodeInstanceId} 属于需求 ${context.requirementId}，与当前会话所在需求 ${requirementId} 不一致。`,
      )
    }
  }
}

/**
 * 按存储根与需求 ID 拼 workspace 路径（纯函数，便于断言）。
 * @param storageRoot - 存储根
 * @param requirementId - 需求 ID
 * @returns 绝对路径
 */
export function resolveWorkspace(storageRoot: string, requirementId: number): string {
  const root = storageRoot.replace(/[\\/]+$/, '')
  return `${root}/${WS_DIR}/${requirementId}`
}

/**
 * 探测窗口：从需求 ID 起向后找一段节点实例 ID。
 *
 * 为什么从需求 ID 起：两者都是自增主键，需求创建后紧接着创建它的节点，
 * 节点 ID 一定大于需求 ID；一个需求最多几十个节点，因此窗口 256 足够覆盖
 * （每轮只是一批很轻的 GET，且只在新需求还没有输出目录时发生一次）。
 * @param requirementId - 需求 ID
 * @returns 候选节点实例 ID（升序）
 */
export function probeWindow(requirementId: number, size = 256): number[] {
  const ids: number[] = []
  for (let id = requirementId; id < requirementId + size; id += 1) ids.push(id)
  return ids
}

/**
 * 从目录名里挑出节点 ID 候选并按"新节点优先"排序（ID 越大越晚创建）。
 * @param names - `.workbench/` 下的目录名
 * @returns 节点 ID 数组
 */
export function modelDirNames(names: string[]): number[] {
  return names
    .filter(name => /^\d+$/.test(name))
    .map(Number)
    .sort((left, right) => right - left)
}
