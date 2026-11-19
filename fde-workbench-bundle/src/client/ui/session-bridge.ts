/**
 * DSH 会话与业务「需求会话」之间的桥。
 *
 * 背景（docs/api.md §9、architecture.md §4.3）：会话的权威在 DSH，业务库只存「需求 ↔ DSH 会话」的对应关系。
 * 本模块负责两件事：
 * 1. **新建会话并登记**：`uiWorkspace.connectWorkspace`（官方导航动作）创建一个 DSH 会话并拿到 SessionId，
 *    随后 POST `/api/requirements/{rid}/sessions` 登记到当前节点（PRD AC-X2.1）。
 * 2. **会话列表合并**：把业务侧登记的会话（带标题、最后活跃时间、是否节点默认）与 DSH 侧会话摘要
 *    （`ctx.sessions.list`）合并成一份「本需求会话」清单，供会话切换器展示。
 *
 * 边界：本模块不做 retain / 渲染（那是 `NodeChatView` → `NodeSessionView` 的职责）。
 */
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import type { SessionVO } from '../api/types.ts'
import type { WorkbenchSessionHost } from '../store/context.tsx'

/** 新建会话的结果：DSH 会话标识 + 业务登记（可能失败）。 */
export interface SessionCreateResult {
  /** DSH 会话标识；创建失败为 undefined */
  readonly dshSessionId: SessionId | string | undefined
  /** 登记失败时的错误文案（DSH 会话已建好但业务侧未记住） */
  readonly registerError?: string | undefined
}

/** 会话切换器的一项。 */
export interface SessionChoice {
  /** DSH 会话标识（字符串形式） */
  readonly dshSessionId: string
  /** 展示标题（业务标题 > DSH 会话标题 > 截断的会话 ID） */
  readonly title: string
  /** 最后活跃时间（ISO 串，可能为空） */
  readonly lastActiveAt: string | undefined
  /** 是否是当前节点的默认会话 */
  readonly isNodePrimary: boolean
  /** 业务侧会话 ID（DSH 会话尚未登记时为 undefined） */
  readonly businessId: number | undefined
}

/**
 * 把业务侧会话与 DSH 侧会话合并成一份清单。
 *
 * 合并规则：
 * - 以业务侧顺序（lastActiveAt 倒序）为主，标题优先用业务标题；
 * - DSH 会话标题（`summary.displayTitle`）作为兜底；
 * - 节点默认会话（`defaultSessionId`）提到第一位，并标记 `isNodePrimary`。
 *
 * @param sessions - 业务侧会话（已按 lastActiveAt 倒序）
 * @param dshTitles - DSH 会话标识 → 标题（来自 `ctx.sessions.list` 的摘要）
 * @param defaultSessionId - 节点默认会话
 * @returns 展示用清单
 */
export function mergeSessionChoices(
  sessions: readonly SessionVO[],
  dshTitles: ReadonlyMap<string, string>,
  defaultSessionId: string | null | undefined,
): SessionChoice[] {
  const choices: SessionChoice[] = sessions.map(session => {
    const dshTitle = dshTitles.get(session.dshSessionId)
    const title = session.title ?? dshTitle ?? shortenSessionId(session.dshSessionId)
    return {
      dshSessionId: session.dshSessionId,
      title,
      lastActiveAt: session.lastActiveAt ?? undefined,
      isNodePrimary: session.isNodePrimary,
      businessId: session.id,
    }
  })
  // 节点默认会话排最前（AC-F7.4：默认打开节点绑定优先的会话）
  const index = choices.findIndex(choice => choice.dshSessionId === defaultSessionId)
  if (index > 0) {
    const [current] = choices.splice(index, 1)
    if (current !== undefined) choices.unshift(current)
  }
  return choices
}

/**
 * 会话标识太长时截断展示（DSH 侧标题缺失时的兜底）。
 * @param id - 会话标识
 */
export function shortenSessionId(id: string): string {
  return id.length <= 16 ? id : `${id.slice(0, 8)}…${id.slice(-4)}`
}

/**
 * 读取 DSH 会话摘要的标题表。
 * @param host - 会话宿主
 * @returns 会话标识 → 标题
 */
export function dshSessionTitles(host: WorkbenchSessionHost): Map<string, string> {
  const state = host.sessions.list.getSnapshot()
  const titles = new Map<string, string>()
  for (const id of state.ids) {
    const summary = state.byId[id]
    const title = summary?.displayTitle
    if (typeof title === 'string' && title !== '') titles.set(String(id), title)
  }
  return titles
}
