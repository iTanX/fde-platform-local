/** 工作台会话列表的来源为业务登记，DSH 摘要只补充标题。 */
import type { SessionVO } from '../api/types.ts'
import type { WorkbenchSessionHost } from '../store/context.tsx'

export interface SessionChoice {
  readonly dshSessionId: string
  readonly title: string
  readonly lastActiveAt: string | undefined
  readonly isNodePrimary: boolean
  readonly businessId: number
}

/** 沿用后端的活跃时间顺序。默认标记取自当前节点详情或刚确认的选择。 */
export function mergeSessionChoices(
  sessions: readonly SessionVO[],
  dshTitles: ReadonlyMap<string, string>,
  defaultSessionId: string | null,
): SessionChoice[] {
  return sessions.filter(session => !session.archived).map(session => ({
    dshSessionId: session.dshSessionId,
    title: dshTitles.get(session.dshSessionId) ?? session.title ?? shortenSessionId(session.dshSessionId),
    lastActiveAt: session.lastActiveAt ?? undefined,
    isNodePrimary: session.dshSessionId === defaultSessionId,
    businessId: session.id,
  }))
}

/** 空白会话还没有标题，以 ID 区分同一工作区的多个空白会话。 */
function shortenSessionId(id: string): string {
  return id.length <= 16 ? id : `${id.slice(0, 8)}…${id.slice(-4)}`
}

export function dshSessionTitles(host: WorkbenchSessionHost): Map<string, string> {
  const state = host.sessions.list.getSnapshot()
  const titles = new Map<string, string>()
  for (const id of state.ids) {
    const title = state.byId[id]?.title
    if (title) titles.set(id, title)
  }
  return titles
}
