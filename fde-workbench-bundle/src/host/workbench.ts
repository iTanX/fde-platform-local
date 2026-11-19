/**
 * 节点目录：把「会话 → 需求 → 当前节点」的解析集中起来，避免四个工具各写一遍。
 *
 * 为什么需要它：工具入参里的 `nodeInstanceId` 是可选的，而
 * `GET /internal/nodes/{nid}/context` 只认节点实例 ID——"当前节点是哪个"必须有人记住。
 * 记住的方式：**会话(agent) + cwd → requirementId → 当前节点上下文（缓存）**。
 *
 * 当前节点的来源（按优先级）：
 *   1. 模型显式给的 `nodeInstanceId`（最可信，多会话并行时唯一安全）；
 *   2. 该需求的缓存（上一次解析出的节点上下文；节点推进后由工具调用 `invalidate` 失效）；
 *   3. **后端给出的节点清单**（`GET /internal/requirements/{rid}/nodes`）：一次拿到该需求全部
 *      节点实例的 id / seq / name / status，从中选当前节点，再取它的 `/context` 快照。
 *
 * 为什么不用"本地 `.workbench/<nid>/` 目录 + 逐步探测节点 ID"的旧做法：那是节点清单接口出现前的
 * 临时方案，有两个根本问题——(a) 请求量大：新需求 / 新节点还没有本地输出目录时，要从需求 ID 起
 * 连续试探最多 256 个节点 ID，每个都是一次 `GET /context`，绝大多数必然 404；(b) 语义不清：
 * "节点实例 ID 紧跟在需求 ID 之后、落在一段窗口内"是依赖自增主键相邻的巧合事实，不是契约，
 * 一旦 ID 分配方式变化（批量导入、其它表插入交错）就会静默失败，且失败时只能报"找不到当前节点"。
 * 现在由后端按需求直接回答，请求数固定为 1 次清单 + 1 次上下文，且不再依赖任何 ID 规律。
 *
 * 一个需求一个 workspace（docs/plugin-integration.md §5.1），节点在需求内串行，
 * 所以缓存不会把两个节点搞混；真要跨节点操作，显式传 `nodeInstanceId` 即可。
 */

import type { Agent } from '@deepseek-ai/dsh-agent'
import type {} from '@deepseek-ai/dsh-system-prompt'
import type {} from '@deepseek-ai/dsh-working-directory'
import { BackendClient } from './backend.ts'
import type { NodeBrief, NodeContext } from './backend.ts'
import { WS_DIR, parseWorkspaceCwd } from './paths.ts'
import type { Settings } from './settings.ts'

/**
 * 判断异常是否为"取消"。
 * <p>
 * 取消必须原样抛出去：调用方（DSH）用 AbortSignal 表达"这次发现作废"，
 * 把它当成普通失败吞掉会让上层拿到空目录并缓存下来。
 *
 * @param error - 捕获到的异常
 * @returns 是否为取消
 */
function isAbort(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

/** 节点状态里的"当前节点"（与后端 `RequirementNode.STATUS_CURRENT` 一致）。 */
const STATUS_CURRENT = 'current'

/** 节点状态里的"已完成"（与后端 `RequirementNode.STATUS_DONE` 一致）。 */
const STATUS_DONE = 'done'

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
    const discovered = await this.locateCurrentNode(requirementId, signal)
    this.remember(discovered)
    return discovered
  }

  /**
   * 按需求 ID 求当前节点（prompt 段用：那里只有需求 ID，没有会话 cwd）。
   *
   * @param requirementId - 需求 ID
   * @param signal - 取消信号
   * @returns 后端当前的节点上下文
   */
  async resolveNodeByRequirement(requirementId: number, signal?: AbortSignal): Promise<NodeContext> {
    // 用户可在页面推进节点。每次组装会话上下文都按后端状态重新定位。
    const discovered = await this.locateCurrentNode(requirementId, signal)
    this.assertSameRequirement(discovered, requirementId)
    this.remember(discovered)
    return discovered
  }

  /**
   * 按会话 cwd 解析当前节点（技能发现与 prompt 段共用）。
   * <p>
   * 只认 `<storageRoot>/ws/<id>` 形状的 cwd：普通会话（任意目录）一律返回 `undefined`，
   * 既不发请求，也不会把节点技能暴露给无关会话。
   *
   * @param cwd - 会话工作目录（可能为空：尚未确定 cwd 的会话）
   * @param signal - 取消信号
   * @returns 节点上下文；cwd 不属于需求 workspace 或解析失败时为 `undefined`
   */
  async resolveNodeByCwd(cwd: string | undefined, signal?: AbortSignal): Promise<NodeContext | undefined> {
    const parsed = parseWorkspaceCwd(cwd, this.settings.storageRoot)
    if (parsed === undefined) return undefined
    try {
      return await this.resolveNodeByRequirement(parsed.requirementId, signal)
    } catch (error) {
      // 后端不可达或需求刚被删除：技能发现退化为"没有节点技能"，由工具侧给出可读错误
      if (isAbort(error)) throw error
      return undefined
    }
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
   * 没有 nodeInstanceId 时定位"当前节点"：先按需求拉一次节点清单，再取选中节点的上下文。
   *
   * 选法（与工作台页面口径一致）：
   *   1. 优先 `status === 'current'` 的节点——需求进行中时它就是要办理的节点；
   *   2. 需求已完成时没有 current 节点，取**最后一个** `done` 节点，让模型在只读状态下
   *      仍能看到"最后一环"的产出与上下文（后端会把 `readonly` 置为 true，只读由后端判定）；
   *   3. 两者都没有（理论上不会出现：清单来自同一份数据）→ 明确报错，让模型显式传 ID，
   *      **不**回退到任何猜测式探测。
   *
   * 失败处理：清单请求失败时抛出带后端原因的中文错误——静默吞掉会退化成"找不到节点"，
   * 把"后端不可用 / 令牌不对 / 需求已删除"这些真实原因掩盖掉，模型与用户都无从下手。
   *
   * @param requirementId - 需求 ID（来自会话 cwd）
   * @param signal - 取消信号
   * @returns 选中节点的上下文（workspace 以后端为准）
   * @throws Error 中文可读错误
   */
  private async locateCurrentNode(requirementId: number, signal?: AbortSignal): Promise<NodeContext> {
    let listing
    try {
      listing = await this.client.requirementNodes(requirementId, signal)
    } catch (error) {
      throw new Error(
        `无法获取需求 ${requirementId} 的节点列表：${errorText(error)}。`
        + '请确认后端已启动、插件 internalToken 正确，且该需求仍然存在。'
        + '也可以先在工作台节点页确认节点，再调用时显式给出 nodeInstanceId。',
      )
    }
    const chosen = this.pickCurrentNode(requirementId, listing.nodes, listing.requirementStatus)
    return await this.client.nodeContext(chosen.nodeInstanceId, signal)
  }

  /**
   * 从节点清单里选出要操作的节点（纯逻辑，便于脱离网络断言）。
   * @param requirementId - 需求 ID（仅用于错误提示）
   * @param nodes - 节点摘要（后端按 seq 升序返回）
   * @param requirementStatus - 需求状态（in_progress / completed）
   * @returns 选中的节点摘要
   * @throws Error 清单为空或没有可定位的节点
   */
  private pickCurrentNode(requirementId: number, nodes: NodeBrief[], requirementStatus: string): NodeBrief {
    if (nodes.length === 0) {
      throw new Error(`需求 ${requirementId} 下没有任何节点实例，无法确定当前节点。请联系用户重新创建需求。`)
    }
    const current = nodes.find(node => node.status === STATUS_CURRENT)
    if (current !== undefined) return current
    // 需求已完成：没有 current，取最后一个 done 节点做只读查看（后端会返回 readonly=true）
    const done = nodes.filter(node => node.status === STATUS_DONE)
    const last = done[done.length - 1]
    if (last !== undefined) return last
    throw new Error(
      `需求 ${requirementId} 的节点清单里既没有进行中的节点，也没有已完成的节点`
      + `（需求状态 ${requirementStatus}，节点：${nodes.map(node => `#${node.nodeInstanceId}「${node.name}」${node.status}`).join('、')}）。`
      + '请在调用时给出 nodeInstanceId（工作台节点页可见）。',
    )
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

/** 把异常转成一行文本（错误提示用）。 */
function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
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
