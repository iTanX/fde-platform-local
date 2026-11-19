/**
 * 工作台外壳（`main` keyed 面板的根组件）。
 *
 * 面板占满中央主区域、不含 DSH 侧栏，因此这里自带一条标题栏：
 * 标题「Web 工作台」+「返回」按钮（回到当前会话），下方是内容区。
 * 内容区本阶段只放占位文案与一个真实 DSH 会话的嵌入冒烟（NodeSessionView）。
 */
import type { ILayout } from '@deepseek-ai/dsh-client-ui-layout/client'
import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import { NodeSessionView, type NodeChatOwnerProps, type SessionHost } from '../session/NodeSessionView.tsx'

/** 工作台面板的注入参数：全部来自 `ctx`，组件内不读浏览器全局。 */
export interface WorkbenchMainInjected {
  /** `ctx.layout`：返回按钮用 `selectPanel(null)` 切回当前会话。 */
  readonly layout: Pick<ILayout, 'selectPanel'>
  /** 会话宿主：`ctx.sessions`。 */
  readonly sessionHost: SessionHost
  /** 新建会话（内部走官方 `ctx.uiWorkspace.startSession`）。 */
  readonly createSession: () => void
}

/** 工作台外壳 props：`main` 槽位运行期参数 + 注入参数 + 本插件命名空间的 `t` + 子槽位渲染能力。 */
export type WorkbenchMainProps = PropsRuntime<'main'>
  & WorkbenchMainInjected
  & PropsLocale<'workbench'>
  & {
    /**
     * 渲染本面板在 children 里声明的 `workbench.node.chat` 槽位（会话作用域）。
     * 旧写法用的是 `renderFactorySlot('workbench.node.chat', …)`，但 `workbench.node.chat`
     * 当时是「工厂 + 局部槽位」的结构，真正被渲染的局部槽位名工厂不存在 → 静默 null。
     */
    readonly renderSlot: (name: 'workbench.node.chat', owner: NodeChatOwnerProps) => React.ReactNode
  }

/**
 * 渲染工作台外壳。
 * @param props - 槽位参数、注入参数、翻译函数与子槽位渲染能力。
 */
export function WorkbenchMain({ t, layout, sessionHost, createSession, renderSlot }: WorkbenchMainProps) {
  // 「返回」= 取消面板选择；ui-layout 切回当前会话。selectPanel 只对未注册 id 抛错，
  // 传 null 永远安全，所以这里不需要 try/catch。
  const onBack = (): void => {
    layout.selectPanel(null)
  }
  return (
    <div
      data-fde-workbench-panel=""
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        minHeight: 0,
        background: 'var(--dsw-alias-bg-base, #fff)',
        color: 'var(--dsw-alias-text-primary, #1f1f1f)',
      }}
    >
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '12px',
          padding: '10px 16px',
          borderBottom: '1px solid var(--dsw-alias-border-subtle, rgba(0,0,0,0.08))',
          flex: '0 0 auto',
        }}
      >
        <button
          type="button"
          onClick={onBack}
          title={t('workbench.backHint')}
          style={{
            appearance: 'none',
            border: '1px solid var(--dsw-alias-border-subtle, rgba(0,0,0,0.12))',
            background: 'transparent',
            color: 'inherit',
            borderRadius: '6px',
            padding: '4px 12px',
            font: 'inherit',
            fontSize: '13px',
            cursor: 'pointer',
          }}
        >
          {t('workbench.back')}
        </button>
        <h1 data-fde-workbench-title="" style={{ margin: 0, fontSize: '15px', fontWeight: 600 }}>
          {t('workbench.title')}
        </h1>
      </header>
      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <section style={{ flex: '0 0 auto', padding: '12px 16px 0', fontSize: '13px', opacity: 0.75 }}>
          <p style={{ margin: 0 }}>{t('workbench.placeholder')}</p>
          <p style={{ margin: '4px 0 0' }}>{t('workbench.smokeHint')}</p>
        </section>
        {/* 会话嵌入冒烟：占满剩余高度，内部由 DSH 会话自己滚动。 */}
        <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <NodeSessionView
            t={t}
            host={sessionHost}
            onCreateSession={createSession}
            renderSlot={renderSlot}
          />
        </div>
      </div>
    </div>
  )
}
