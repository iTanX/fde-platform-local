/**
 * 节点页「会话」视图的冒烟实现 —— 把**一个真实 DSH 会话**嵌进工作台面板的非主面板位置。
 *
 * 依据 plugin-integration.md §4.1.1，只使用官方公开扩展点：
 * 1. `ctx.sessions.retain(sessionId, { source })` 取得会话引用（任意 SessionId，不限子会话）；
 * 2. `<SessionProvider session={reference}>` 声明会话作用域；
 * 3. `renderFactorySlot('conversation.content', { variant: 'embedded', phase, hero }, { slots: { views } })`
 *    渲染官方会话内容工厂（输入框、消息流、工具卡片、运行状态全部由 DSH 自己渲染，
 *    所以「可输入 / 可滚动 / Esc Esc 停止」这些原生快捷键保持生效）；
 * 4. `views` 局部槽位固定选 Chat 视图（与 ui-subagent 的官方先例 sidebar-chat 完全一致）。
 *
 * 为什么用「自建工厂」承载：
 *   `renderFactorySlot` 只有在*工厂定义*内部调用时才拿到正确的槽位归属（`boundRenderFactorySlot(definition)`），
 *   普通 entry 拿到的 renderFactorySlot 绑定在 entry 上，去渲染 `conversation.content` 会被槽位归属校验拒绝。
 *   官方 sidebar-chat 的做法是：在会话作用域声明一个单槽位（`sidebar.chat.conversation`），
 *   其组件用 `renderSlot` 渲染，再由该槽位里的组件调用 `conversation.content` 工厂。
 *   这里照搬同一结构，改用本插件自己的工厂 `workbench.node.chat`（输入 `{ reference }`）。
 *
 * 失败态：拿不到会话 / retain 抛错时，只在本区域内显示错误与「新建会话」「重试」，绝不白屏。
 */
import { useCallback, useEffect, useState } from 'react'
import type { ISessions, SessionReference, SessionRetainOptions, SessionSnapshot } from '@deepseek-ai/dsh-api-session-controller/client'
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import type { RenderFactorySlot, TranslateNS } from '@deepseek-ai/dsh-client-ui-slots'

/** 工作台命名空间的翻译函数类型（key 域来自声明的字典）。 */
export type WorkbenchTranslate = TranslateNS<'workbench'>

declare module '@deepseek-ai/dsh-api-session-controller/client' {
  interface SessionReferenceSourceMap {
    /** Web 工作台面板里的会话嵌入视图。 */
    workbenchEmbed: unknown
  }
}

/** `ctx.sessions` 的稳定子集。 */
export interface SessionHost {
  readonly sessions: Pick<ISessions, 'list' | 'retain' | 'refresh'>
}

/** 本插件工厂 `workbench.node.chat` 的输入。 */
export interface NodeChatInput {
  /** 要嵌入的会话引用（由调用方 retain，本组件不负责释放）。 */
  readonly reference: SessionReference
}

/** 嵌入视图的文案与依赖。 */
export interface NodeSessionViewProps {
  /** 工作台命名空间的翻译函数。 */
  readonly t: WorkbenchTranslate
  /** 会话宿主（apply() 里注入的 ctx 引用）。 */
  readonly host: SessionHost
  /** 新建会话（走官方 ctx.uiWorkspace.startSession）。 */
  readonly onCreateSession: () => void
  /** 由渲染框架注入：渲染本插件声明的工厂槽位。 */
  readonly renderFactorySlot: (name: 'workbench.node.chat', input: NodeChatInput) => React.ReactNode
}

/** 会话解析结果。 */
type ResolveState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly id: SessionId }
  | { readonly kind: 'empty' }
  | { readonly kind: 'failed'; readonly message: string }

/**
 * 挑选要嵌入的会话：侧栏当前选中的会话（uiWorkspace 记录）优先，否则最近活跃的非子会话。
 * 本阶段用列表顺序（Host 按最近活跃排序）即可，T5-3 会替换为「按节点绑定的会话」。
 * @param sessions - 会话服务。
 */
function pickSessionId(sessions: SessionHost['sessions']): SessionId | undefined {
  const state = sessions.list.getSnapshot()
  return state.ids.find((id) => state.byId[id]?.origin !== 'subagent')
}

/** 把任意异常转成一行可展示文字。 */
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

const linkButton = {
  appearance: 'none', border: 'none', background: 'transparent', color: 'inherit', font: 'inherit',
  padding: 0, textDecoration: 'underline', cursor: 'pointer',
} as const

const primaryButton = {
  appearance: 'none', border: '1px solid var(--dsw-alias-border-subtle, rgba(0,0,0,0.12))',
  background: 'transparent', color: 'inherit', borderRadius: '6px', padding: '3px 10px', font: 'inherit',
  cursor: 'pointer', marginTop: '8px',
} as const

/** 提示块。 */
function Notice({ children }: { readonly children: React.ReactNode }) {
  return <div style={{ padding: '12px 16px', fontSize: '13px', lineHeight: 1.6, opacity: 0.8 }}>{children}</div>
}

/**
 * 节点页「会话」视图（冒烟版）。
 * @param props - 翻译函数、会话宿主、新建会话回调与工厂槽位渲染能力。
 */
export function NodeSessionView({ t, host, onCreateSession, renderFactorySlot }: NodeSessionViewProps) {
  const { sessions } = host
  const [state, setState] = useState<ResolveState>({ kind: 'loading' })
  const [reference, setReference] = useState<SessionReference | undefined>(undefined)
  const [retryToken, setRetryToken] = useState(0)

  // 第 1 步：解析要嵌入的 sessionId。
  useEffect(() => {
    let cancelled = false
    setState({ kind: 'loading' })
    void (async () => {
      try {
        await sessions.refresh()
        if (cancelled) return
        const id = pickSessionId(sessions)
        setState(id === undefined ? { kind: 'empty' } : { kind: 'ready', id })
      } catch (error) {
        if (!cancelled) setState({ kind: 'failed', message: errorMessage(error) })
      }
    })()
    return () => {
      cancelled = true
    }
  }, [sessions, retryToken])

  // 第 2 步：持有会话引用；会话切换或卸载时释放。新引用就绪前保留旧引用，避免闪断。
  useEffect(() => {
    if (state.kind !== 'ready') return
    let disposed = false
    let held: SessionReference | undefined
    try {
      const options: SessionRetainOptions = { source: 'workbenchEmbed' }
      held = sessions.retain(state.id, options)
      if (!disposed) setReference(held)
    } catch (error) {
      if (!disposed) setState({ kind: 'failed', message: errorMessage(error) })
    }
    return () => {
      disposed = true
      held?.release()
    }
  }, [sessions, state])

  // 「新建会话」走官方导航动作（与侧栏「新建会话」同一条路径），不调用任何业务接口。
  const createSession = useCallback(() => {
    try {
      onCreateSession()
      setRetryToken(token => token + 1)
    } catch (error) {
      setState({ kind: 'failed', message: errorMessage(error) })
    }
  }, [onCreateSession])

  const retry = useCallback(() => { setRetryToken(token => token + 1) }, [])

  return (
    <div
      data-fde-workbench-session-view=""
      style={{ display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 0, height: '100%' }}
    >
      <div
        style={{
          display: 'flex', alignItems: 'center', gap: '8px', padding: '6px 16px 8px',
          fontSize: '12px', opacity: 0.7, flex: '0 0 auto',
        }}
      >
        <strong style={{ fontWeight: 600 }}>{t('session.heading')}</strong>
        <span data-fde-workbench-session-id="">{state.kind === 'ready' ? state.id : t('session.noSession')}</span>
        <button type="button" onClick={retry} style={linkButton}>{t('session.reload')}</button>
      </div>
      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        {state.kind === 'loading' ? <Notice>{t('session.loading')}</Notice> : null}
        {state.kind === 'empty'
          ? (
            <Notice>
              <div>{t('session.empty')}</div>
              <div>{t('session.emptyHint')}</div>
              <button type="button" onClick={createSession} style={primaryButton}>{t('session.create')}</button>
            </Notice>
          )
          : null}
        {state.kind === 'failed'
          ? (
            <Notice>
              <div data-fde-workbench-error="">{t('session.error', { message: state.message })}</div>
              <div style={{ display: 'flex', gap: '8px', marginTop: '8px' }}>
                <button type="button" onClick={retry} style={linkButton}>{t('session.retry')}</button>
                <button type="button" onClick={createSession} style={primaryButton}>{t('session.create')}</button>
              </div>
            </Notice>
          )
          : null}
        {state.kind === 'ready' && reference !== undefined
          ? renderFactorySlot('workbench.node.chat', { reference })
          : null}
      </div>
    </div>
  )
}

/** 嵌进工厂里的会话内容（`conversation.content` 的调用方）。 */
export interface NodeChatConversationProps extends NodeChatInput, NodeChatSeat {
  /** 渲染 `conversation.content` 工厂。 */
  readonly renderFactorySlot: RenderFactorySlot
}

/** 固定 Chat 视图（与官方 sidebar-chat 同款）。 */
function FixedChatView(props: {
  readonly renderSlot: (key: 'conversation.session', owner: { view: 'chat' }) => React.ReactNode
}) {
  return <>{props.renderSlot('conversation.session', { view: 'chat' })}</>
}

/**
 * 工厂发生体的根：把作用域座席转交给局部槽位组件，由它渲染会话内容。
 * 这样 `conversation.content` 的调用者就是本插件工厂的局部槽位组件（归属校验通过），
 * 与官方 ui-subagent 的 `sidebar.chat.conversation` 结构一致。
 * @param props - 作用域座席 + 工厂渲染能力。
 */
export function NodeChatListConversation(props: NodeChatListConversationProps) {
  const { reference, renderFactorySlot } = props
  // 局部槽位名由 Factory 声明提供，不属于 SlotFactoryMap；这里的 key 域类型只收 Factory 名，
  // 因此把这个合法的局部槽位键显式断言一次（运行期由 renderFactorySlot 校验归属）。
  return renderFactorySlot('workbench.node.chat.conversation' as 'workbench.node.chat', {
    reference,
  }, { slots: { conversation: NodeChatConversation as (props: unknown) => React.ReactNode } })
}

/** `workbench.node.chat.conversation` 局部槽位组件的 props：作用域座席 + 引用 + 工厂能力。 */
export type NodeChatListConversationProps = NodeChatInput & NodeChatSeat & {
  /** 渲染局部槽位 `workbench.node.chat.conversation`。 */
  readonly renderFactorySlot: RenderFactorySlot
}

/** 会话作用域座席（由渲染框架按作用域注入）。 */
interface NodeChatSeat {
  readonly useSession: (selector: (value: SessionSnapshot) => unknown) => unknown
  readonly useConversation: (selector: (value: { readonly activeTargets: ReadonlySet<unknown> }) => unknown) => unknown
  readonly useSessions: (selector: (state: { readonly byId: Record<string, { readonly blank?: boolean }> }) => unknown) => unknown
  readonly SessionProvider: (props: { readonly session: SessionReference; readonly children: React.ReactNode }) => React.ReactNode
}

/**
 * 渲染官方 `conversation.content` 工厂（variant = 'embedded'）。
 * @param props - 会话引用、会话作用域座席与工厂渲染能力。
 */
export function NodeChatConversation(props: NodeChatConversationProps) {
  const { reference, useSession, useConversation, useSessions, SessionProvider, renderFactorySlot } = props
  const session = useSession((value: SessionSnapshot) => value) as SessionSnapshot
  const activeTargets = useConversation(value => value.activeTargets) as ReadonlySet<unknown>
  // §4.1.1 第 2 条：与主面板同规则 —— 有活跃目标 / 已有内容 / 正在运行 → active；
  // 加载中 → settling；新建空白会话 → hero。
  const active = activeTargets.size > 0
    || (!session.blank && !session.awaitingFirstTurn)
    || session.running
  const shellPhase = active ? 'active' : session.promptAttempted ? 'engaging' : 'blank'
  const summaryBlank = useSessions(state => state.byId[session.sessionId]?.blank) as boolean | undefined
  const settling = shellPhase === 'blank' && session.openState === 'loading' && summaryBlank !== true
  const hero = shellPhase === 'blank' && (session.openState === 'open' || summaryBlank === true)
  const phase = settling ? 'settling' : hero ? 'hero' : 'active'
  // phase / hero / openState 的实际取值挂到容器上，冒烟时可在 DevTools 里直接确认。
  return (
    <div
      data-fde-workbench-embed=""
      data-embed-variant="embedded"
      data-embed-phase={phase}
      data-embed-hero={String(hero)}
      data-embed-open-state={session.openState}
      style={{ display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 0, height: '100%' }}
    >
      <SessionProvider session={reference}>
        {renderFactorySlot('conversation.content', { variant: 'embedded', phase, hero }, {
          slots: { views: FixedChatView },
        })}
      </SessionProvider>
    </div>
  )
}
