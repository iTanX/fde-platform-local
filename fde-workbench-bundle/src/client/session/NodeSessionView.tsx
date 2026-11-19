/**
 * 节点页「会话」视图的冒烟实现 —— 把**一个真实 DSH 会话**嵌进工作台面板的非主面板位置。
 *
 * 依据 plugin-integration.md §4.1.1，只使用官方公开扩展点：
 * 1. `ctx.sessions.retain(sessionId, { source })` 取得会话引用（任意 SessionId，不限子会话）；
 * 2. `<SessionProvider session={reference}>` 声明会话作用域；
 * 3. `renderFactorySlot('conversation.content', { variant: 'embedded', phase, hero }, { slots: { views: FixedChatView } })`
 *    渲染官方会话内容工厂（输入框、消息流、工具卡片、运行状态全部由 DSH 自己渲染，
 *    所以「可输入 / 可滚动 / Esc Esc 停止」这些原生快捷键保持生效）；
 * 4. `views` 局部槽位固定选 Chat 视图（与 ui-subagent 的官方先例 sidebar-chat 完全一致）。
 *
 * 为什么由「会话作用域槽位」承载（而不是自建工厂）：
 *   `renderFactorySlot` 由渲染框架按**注册项**发放（`boundRenderFactorySlot(entry)`），
 *   任何槽位组件拿到的都绑定在自己的注册项上，所以真正的归属校验是
 *   `FactoryOutlet` 去 `host.factoryOf(name)` 查**工厂定义**是否存在：
 *   - 工厂根里回头渲染自己的局部槽位名（旧写法 `'workbench.node.chat.conversation'`），
 *     `factoryOf()` 查不到 → `if (definition === undefined) return <>{fallback ?? null}</>`，
 *     无报错、无内容，这就是面板空白的原因；
 *   - 在槽位组件里渲染 `'conversation.content'`，`factoryOf()` 能查到官方工厂，正常渲染。
 *   结构照搬官方 ui-subagent（`sidebar-chat/index.tsx` 的 `registerSidebarChat()`）：
 *   外层注册项声明会话子槽位 → 内层槽位注册项的组件（官方叫 `ConversationSlotPanel`，
 *   这里叫 `WorkbenchConversationPanel`）里 `<SessionProvider>` + 调 `conversation.content` 工厂。
 *
 * 失败态：拿不到会话 / retain 抛错时，只在本区域内显示错误与「新建会话」「重试」，绝不白屏。
 */
import { useCallback, useEffect, useState } from 'react'
import type { ISessions, SessionReference, SessionRetainOptions } from '@deepseek-ai/dsh-api-session-controller/client'
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import type { PropsRenderFactories, PropsRenderSlots, PropsRuntime, TranslateNS } from '@deepseek-ai/dsh-client-ui-slots'

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

/** 本插件嵌入槽位 `workbench.node.chat` 的 owner 参数（由外层 `renderSlot` 在渲染点传入）。 */
export interface NodeChatOwnerProps {
  /** 要嵌入的会话引用（由调用方 retain，本组件不负责释放）。 */
  readonly reference: SessionReference
}

// 槽位 owner 参数的单一来源：SlotMap 里用 `owner: NodeChatOwnerProps` 声明过，
// 这里再取一次交给 renderSlot 的 owner 形参类型（值相等，保证两处不会漂移）。

/** 嵌入视图的文案与依赖。 */
export interface NodeSessionViewProps {
  /** 工作台命名空间的翻译函数。 */
  readonly t: WorkbenchTranslate
  /** 会话宿主（apply() 里注入的 ctx 引用）。 */
  readonly host: SessionHost
  /** 新建会话（走官方 ctx.uiWorkspace.startSession）。 */
  readonly onCreateSession: () => void
  /**
   * 由渲染框架注入：渲染本插件声明的槽位 `workbench.node.chat`。
   * 注意它渲染的是**槽位**（kind: 'single'），不是工厂（旧写法误把它当工厂名用）。
   */
  readonly renderSlot: (name: 'workbench.node.chat', owner: NodeChatOwnerProps) => React.ReactNode
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
 * @param props - 翻译函数、会话宿主、新建会话回调与子槽位渲染能力。
 */
export function NodeSessionView({ t, host, onCreateSession, renderSlot }: NodeSessionViewProps) {
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
          ? renderSlot('workbench.node.chat', { reference })
          : null}
      </div>
    </div>
  )
}

/** 固定 Chat 视图（与官方 sidebar-chat 的 FixedChatConversationView 同款）。 */
function FixedChatView(props: {
  readonly renderSlot: (key: 'conversation.session', owner: { view: 'chat' }) => React.ReactNode
}) {
  return <>{props.renderSlot('conversation.session', { view: 'chat' })}</>
}

/**
 * 外层槽位 `workbench.node.chat` 的组件（官方 `SidebarChatTab` 的角色）：
 * 只负责「绑定会话引用 + 渲染会话内容座席」，本身不碰 conversation.content。
 *
 * props 来自 `PropsRuntime<'workbench.node.chat'>`（会话作用域座席 + owner 参数）
 * 与 `PropsRenderSlots<'workbench.node.chat.conversation'>`（自声明子槽位的 renderSlot
 * 与 SessionProvider 座位）。**SessionProvider 只能从 PropsRenderSlots 拿到**：
 * 它由渲染框架在「注册项声明了非 root 子槽位」时注入，官方 SidebarChatTabProps 同款写法。
 */
export type WorkbenchChatShellProps = PropsRuntime<'workbench.node.chat'>
  & PropsRenderSlots<'workbench.node.chat.conversation'>

/**
 * 会话外壳：`<SessionProvider session={reference}>` 把外层传进来的会话引用绑定成会话作用域，
 * 再渲染内容座席。宽度/高度撑满，交给 conversation.content 自己滚动。
 * @param props - 会话座席（SessionProvider / renderSlot）+ owner 参数（reference）。
 */
export function WorkbenchChatShell({ reference, SessionProvider, renderSlot }: WorkbenchChatShellProps) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 0, height: '100%' }}>
      <SessionProvider session={reference}>
        {renderSlot('workbench.node.chat.conversation', {})}
      </SessionProvider>
    </div>
  )
}

/**
 * 会话内容座席 `workbench.node.chat.conversation` 的组件（官方 `ConversationSlotPanel` 的角色）：
 * 在**会话作用域槽位组件**里直接渲染官方 `conversation.content` 工厂（variant = 'embedded'）。
 *
 * 为什么这里调 `renderFactorySlot` 合法（详见文件头）：归属校验是
 * `FactoryOutlet` 去 `host.factoryOf('conversation.content')` 查工厂定义，
 * 该工厂由 ui-conversation 注册，存在 → 正常渲染。
 * 旧写法把**局部槽位名** `workbench.node.chat.conversation` 当工厂名传进去，
 * `factoryOf()` 返回 undefined → `return <>{fallback ?? null}</>` → 无报错、无内容的空白区。
 *
 * props：`PropsRuntime<'workbench.node.chat.conversation'>` 给会话座席
 * （sessionId / useSession / useConversation / useSessions），`PropsRenderFactories` 给 renderFactorySlot。
 */
export type WorkbenchConversationPanelProps = PropsRuntime<'workbench.node.chat.conversation'>
  & PropsRenderFactories

/**
 * 渲染官方 `conversation.content` 工厂（variant = 'embedded'）。
 * @param props - 会话座席与工厂渲染能力。
 */
export function WorkbenchConversationPanel(props: WorkbenchConversationPanelProps) {
  const { sessionId, useSession, useConversation, useSessions, renderFactorySlot } = props
  const session = useSession(value => value)
  const conversation = useConversation(value => value)
  // §4.1.1 第 2 条：与主面板 `ConversationMainPanel` 完全同一套规则 ——
  // 有活跃目标 / 已有内容 / 正在运行 → active；加载中 → settling；新建空白会话 → hero。
  const active = conversation.activeTargets.size > 0
    || (!session.blank && !session.awaitingFirstTurn)
    || session.running
  const shellPhase = active ? 'active' : session.promptAttempted ? 'engaging' : 'blank'
  const summaryBlank = useSessions(state => state.byId[sessionId]?.blank)
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
      {renderFactorySlot('conversation.content', { variant: 'embedded', phase, hero }, {
        slots: { views: FixedChatView },
      })}
    </div>
  )
}
