/**
 * 页面脚手架：页头（返回 + 标题 + 副标题 + 右侧操作）、内容区、以及三态渲染的公共包装。
 *
 * 所有页面都只用这一套版式（architecture.md §4.1：页头 → 视图页签 → 内容区），
 * 这样各页面的视觉密度、分隔线与返回按钮位置保持一致。
 */
import type { ReactNode } from 'react'
import { ErrorState, Loading } from './components.tsx'
import type { WorkbenchTranslate } from '../session/NodeSessionView.tsx'
import { color, contentArea, muted, pageHeader } from './styles.ts'
import type { Loadable } from './use-loadable.ts'

/**
 * 页面外壳。
 * @param props - 可选返回按钮、标题、副标题、页头右侧操作与正文。
 */
export function PageShell({ page, title, subtitle, back, actions, children, tabs }: {
  /** `data-fde-page` 的值（自动化断言用） */
  readonly page: string
  readonly title: ReactNode
  readonly subtitle?: ReactNode
  readonly back?: { readonly label: string; readonly onClick: () => void } | undefined
  readonly actions?: ReactNode
  /** 页头下方的页签（可选） */
  readonly tabs?: ReactNode
  readonly children: ReactNode
}) {
  return (
    <div data-fde-page={page} style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <header style={pageHeader}>
        {back === undefined ? null : (
          <button
            type="button"
            data-fde-back=""
            onClick={back.onClick}
            title={back.label}
            style={{
              appearance: 'none', border: `1px solid ${color.border}`, background: 'transparent',
              color: 'inherit', borderRadius: '6px', padding: '2px 10px', font: 'inherit', fontSize: '12px',
              lineHeight: '18px', cursor: 'pointer', whiteSpace: 'nowrap',
            }}
          >
            {back.label}
          </button>
        )}
        <div style={{ minWidth: 0, flex: '1 1 auto' }}>
          <div data-fde-page-title="" style={{ fontSize: '15px', fontWeight: 600, lineHeight: '20px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {title}
          </div>
          {subtitle === undefined ? null : (
            <div data-fde-page-subtitle="" style={{ ...muted, marginTop: '2px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {subtitle}
            </div>
          )}
        </div>
        {actions === undefined ? null : <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: '0 0 auto' }}>{actions}</div>}
      </header>
      {tabs}
      <div style={contentArea}>{children}</div>
    </div>
  )
}

/**
 * 三态渲染：加载中 → 加载态；失败 → 可重试错误态；成功 → 渲染 children。
 * @param props - 三态结果、重试回调与成功渲染函数。
 */
export function LoadableView<T>({ state, onRetry, retryHint, t, children }: {
  readonly t: WorkbenchTranslate
  readonly state: Loadable<T>
  readonly onRetry: () => void
  /** 失败时的补充说明（如「确认后端已启动」） */
  readonly retryHint?: ((state: Extract<Loadable<T>, { kind: 'failed' }>) => string | undefined) | undefined
  readonly children: (data: T) => ReactNode
}) {
  if (state.kind === 'loading') return <Loading label={t('common.loading')} />
  if (state.kind === 'failed') {
    return <ErrorState message={state.message} hint={retryHint?.(state)} onRetry={onRetry} retryLabel={t('common.retry')} />
  }
  return <>{children(state.data)}</>
}
