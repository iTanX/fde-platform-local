/**
 * 工作台外壳（`main` keyed 面板的根组件）。
 *
 * 面板占满中央主区域、不含 DSH 侧栏，因此自带一条标题栏：标题「Web 工作台」+「返回」（回到当前会话）。
 * 标题栏下方是**页面栈**渲染区：
 *
 * ```text
 * 未登录        → LoginPage
 * 已登录        → projects / project / workflow / requirement / node 之一
 * ```
 *
 * 该组件只做三件事：装配功能域上下文（`WorkbenchProvider`）、渲染当前页面、渲染轻提示。
 * 页面内部的所有请求、状态与交互都在各自的页面组件里，避免外壳变成上帝对象。
 */
import { useCallback, useMemo, type ReactNode } from 'react'
import type { ILayout } from '@deepseek-ai/dsh-client-ui-layout/client'
import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type { WorkbenchApi } from '../api/index.ts'
import type { AppStore } from '../store/app-store.ts'
import { useAppState } from '../store/app-store.ts'
import { WorkbenchProvider, type WorkbenchSessionHost } from '../store/context.tsx'
import { useToasts, ToastHost, ErrorState, type Tone } from '../ui/components.tsx'
import { color } from '../ui/styles.ts'
import type { NodeChatOwnerProps } from '../session/NodeSessionView.tsx'
import { LoginPage } from '../pages/LoginPage.tsx'
import { ProjectsPage } from '../pages/ProjectsPage.tsx'
import { ProjectPage } from '../pages/ProjectPage.tsx'
import { WorkflowEditorPage } from '../pages/workflows/WorkflowEditorPage.tsx'
import { RequirementDetailPage } from '../pages/RequirementDetailPage.tsx'
import { RequirementPage } from '../pages/RequirementPage.tsx'

/** `main` 面板的注入参数：全部来自 `ctx`（在 `index.ts` 的 `inject` 里构造），组件内不读浏览器全局。 */
export interface WorkbenchMainInjected {
  /** `ctx.layout`：标题栏「返回」用 `selectPanel(null)` 切回当前会话。 */
  readonly layout: Pick<ILayout, 'selectPanel'>
  /** 业务接口集合（一个实例，JWT 由它持有） */
  readonly api: WorkbenchApi
  /** 页面栈与当前用户 / 当前项目 */
  readonly store: AppStore
  /** 会话宿主（嵌入 + 新建） */
  readonly sessionHost: WorkbenchSessionHost
}

/** 工作台外壳 props：`main` 槽位运行期参数 + 注入参数 + 本插件命名空间的 `t` + 子槽位渲染能力。 */
export type WorkbenchMainProps = PropsRuntime<'main'>
  & WorkbenchMainInjected
  & PropsLocale<'workbench'>
  & {
    /**
     * 渲染本面板在 children 里声明的 `workbench.node.chat` 槽位（会话作用域）。
     * 节点页的「会话」视图通过它渲染真实的 DSH 会话。
     */
    readonly renderSlot: (name: 'workbench.node.chat', owner: NodeChatOwnerProps) => ReactNode
  }

/**
 * 渲染工作台外壳。
 * @param props - 槽位参数、注入参数、翻译函数与子槽位渲染能力。
 */
export function WorkbenchMain({ t, layout, api, store, sessionHost, renderSlot }: WorkbenchMainProps) {
  const state = useAppState(store)
  const { toasts, push, dismiss } = useToasts()

  // 会话宿主已是 WorkbenchSessionHost（内含 SessionHost 的 sessions 能力），这里只是给它一个短名字
  const host = sessionHost

  /** 轻提示：页面里统一的成功 / 失败反馈。 */
  const toast = useCallback((text: string, tone: Tone = 'neutral'): void => { push(text, tone) }, [push])

  const contextValue = useMemo(() => ({
    api,
    store,
    t,
    sessionHost: host,
    renderSlot,
    toast,
    currentUsername: state.user?.displayName ?? state.user?.username,
  }), [api, store, t, host, renderSlot, toast, state.user])

  /** 标题栏「返回」= 取消面板选择；ui-layout 切回当前会话（selectPanel(null) 永远安全）。 */
  const onBack = (): void => { layout.selectPanel(null) }

  const page = state.stack[state.stack.length - 1]

  return (
    <WorkbenchProvider value={contextValue}>
      <div
        data-fde-workbench-panel=""
        style={{
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
          minHeight: 0,
          background: color.bg,
          color: color.text,
        }}
      >
        <header
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '12px',
            padding: '8px 24px',
            borderBottom: `1px solid ${color.border}`,
            flex: '0 0 auto',
            minHeight: '40px',
            boxSizing: 'border-box',
          }}
        >
          <button
            type="button"
            data-fde-action="leave-panel"
            onClick={onBack}
            title={t('workbench.backHint')}
            style={{
              appearance: 'none',
              border: `1px solid ${color.border}`,
              background: 'transparent',
              color: 'inherit',
              borderRadius: '6px',
              padding: '2px 10px',
              font: 'inherit',
              fontSize: '12px',
              lineHeight: '18px',
              cursor: 'pointer',
            }}
          >
            {t('workbench.back')}
          </button>
          <h1 data-fde-workbench-title="" style={{ margin: 0, fontSize: '14px', fontWeight: 600 }}>
            {t('workbench.title')}
          </h1>
          {state.user === undefined ? null : (
            <span style={{ marginLeft: 'auto', fontSize: '12px', color: color.textMuted }} data-fde-current-user="">
              {state.user.displayName} @{state.user.username}
            </span>
          )}
        </header>

        <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          {state.user === undefined
            ? <div style={{ flex: '1 1 auto', minHeight: 0, overflow: 'auto' }}><LoginPage api={api} store={store} t={t} /></div>
            : page === undefined
              ? <ErrorState message={t('common.empty')} />
              : page.kind === 'projects'
                ? <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}><ProjectsPage store={store} /></div>
                : page.kind === 'project'
                  ? <ProjectPage projectId={page.projectId} tab={page.tab} store={store} />
                  : page.kind === 'workflow'
                    ? <WorkflowEditorPage store={store} projectId={page.projectId} workflowId={page.workflowId} />
                    : page.kind === 'requirement'
                      ? <RequirementDetailPage store={store} projectId={page.projectId} requirementId={page.requirementId} />
                      : (
                        <RequirementPage
                          store={store}
                          requirementId={page.requirementId}
                          nodeId={page.nodeId}
                          view={page.view}
                        />
                      )}
        </div>
        <ToastHost items={toasts} onDismiss={dismiss} />
      </div>
    </WorkbenchProvider>
  )
}
