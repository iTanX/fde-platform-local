/** 在节点页中用 DSH 的会话作用域与 conversation.content 工厂嵌入原生对话。 */
import { useEffect, useState } from 'react'
import type { ISessions, SessionReference } from '@deepseek-ai/dsh-api-session-controller/client'
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import type { PropsRenderFactories, PropsRenderSlots, PropsRuntime, TranslateNS } from '@deepseek-ai/dsh-client-ui-slots'

/** 工作台命名空间的翻译函数。 */
export type WorkbenchTranslate = TranslateNS<'workbench'>

declare module '@deepseek-ai/dsh-api-session-controller/client' {
  interface SessionReferenceSourceMap {
    /** Web 工作台面板里的会话嵌入视图。 */
    workbenchEmbed: unknown
  }
}

export interface SessionHost {
  readonly sessions: Pick<ISessions, 'list' | 'retain' | 'refresh'>
  /** 验证已登记会话存在且属于当前需求的工作区。 */
  readonly resolveSession: (workspacePath: string, dshSessionId: string) => Promise<SessionId>
}

export interface NodeChatOwnerProps {
  /** 调用方持有并释放引用。 */
  readonly reference: SessionReference
}

type EmbedState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly reference: SessionReference }
  | { readonly kind: 'failed'; readonly message?: string }

/** 嵌入调用方指定的会话。调用方以会话 ID 为 key，切换时释放旧引用。 */
export function NodeSessionView({ t, host, workspacePath, dshSessionId, renderSlot }: {
  readonly t: WorkbenchTranslate
  readonly host: SessionHost
  readonly workspacePath: string
  readonly dshSessionId: string
  readonly renderSlot: (name: 'workbench.node.chat', owner: NodeChatOwnerProps) => React.ReactNode
}) {
  const [state, setState] = useState<EmbedState>({ kind: 'loading' })
  const [retryToken, setRetryToken] = useState(0)

  useEffect(() => {
    const acquisition = new AbortController()
    let reference: SessionReference | undefined
    void (async () => {
      try {
        const id = await host.resolveSession(workspacePath, dshSessionId)
        if (acquisition.signal.aborted) return
        reference = host.sessions.retain(id, { source: 'workbenchEmbed', signal: acquisition.signal })
        const binding = await reference.ready
        if (acquisition.signal.aborted) return
        // 历史读取的业务错误会写入快照，ready 仍可正常结束。
        const snapshot = binding.session.getSnapshot()
        if (snapshot.removed) {
          reference.release()
          reference = undefined
          setState({ kind: 'failed' })
          return
        }
        if (snapshot.openError !== null) throw new Error(snapshot.openError.message)
        setState({ kind: 'ready', reference })
      } catch (error) {
        reference?.release()
        reference = undefined
        if (!acquisition.signal.aborted) {
          setState({ kind: 'failed', message: error instanceof Error ? error.message : String(error) })
        }
      }
    })()
    return () => {
      acquisition.abort()
      reference?.release()
      reference = undefined
    }
  }, [host, workspacePath, dshSessionId, retryToken])

  return (
    <div data-fde-workbench-session-view="" style={{ display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 0 }}>
      <span hidden data-fde-workbench-session-id="">{dshSessionId}</span>
      {state.kind === 'loading' ? <div role="status">{t('session.loading')}</div> : null}
      {state.kind === 'failed' ? (
        <div>
          <div data-fde-workbench-error="">{t('session.error', { message: state.message ?? t('session.missing') })}</div>
          <button type="button" onClick={() => {
            setState({ kind: 'loading' })
            setRetryToken(token => token + 1)
          }}>{t('session.retry')}</button>
        </div>
      ) : null}
      {state.kind === 'ready' ? renderSlot('workbench.node.chat', { reference: state.reference }) : null}
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
