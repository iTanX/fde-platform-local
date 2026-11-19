/**
 * 节点页（T5-3 核心，PRD REQ-F4 / F5 / F7）。
 *
 * 结构（architecture.md §2.2 / §4.2）：
 *
 * ```text
 * [← 返回需求]  需求标题 · 状态 · 节点 2/3
 * [ 会话 | 节点信息 ]        ← readonly=true 时只有「节点信息」
 * ──────────────────────────────
 * 「会话」：会话切换器 + 新建会话 + DSH 原生会话（NodeSessionView）
 * 「节点信息」：节点名称 → 上一节点输出 → 节点输入 → 输出项 → 说明（NodeInfoPanel）
 * ```
 *
 * 页签切换只改页面栈里的 `view` 字段，**不跳转、不离开工作台**（AC-F7.1）。
 *
 * 节点推进以服务端节点状态为准，当前节点完成后沿用同一需求的会话进入下一节点。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client.ts'
import type { RequirementNodeVO } from '../api/types.ts'
import { useNavigation, type AppStore } from '../store/app-store.ts'
import { useWorkbench } from '../store/context.tsx'
import { ErrorState, Loading, StatusBadge, UnderlineTabs } from '../ui/components.tsx'
import { PageShell } from '../ui/PageShell.tsx'
import { useLoadable } from '../ui/use-loadable.ts'
import { buttonBase, muted } from '../ui/styles.ts'
import { NodeInfoPanel } from './node/NodeInfoPanel.tsx'
import { NodeSessionPanel } from './node/NodeSessionView.tsx'

/** 轮询间隔（毫秒）：输出由模型提交，页面只能被动发现。 */
const POLL_INTERVAL_MS = 5000

/** 节点页的两个视图（页签名来自文案 key，由页面翻译）。 */
type NodeView = 'session' | 'info'

/**
 * 节点页。
 * @param props - 页面栈 store、项目 / 需求 / 节点 ID 与当前视图。
 */
export function RequirementPage({ store, requirementId, nodeId, view }: {
  readonly store: AppStore
  readonly requirementId: number
  readonly nodeId: number
  readonly view: NodeView
}) {
  const { api, t, toast } = useWorkbench()
  const nav = useNavigation(store)
  const requirement = useLoadable(() => api.requirement.detail(requirementId), [requirementId])
  const node = useLoadable(() => api.node.detail(requirementId, nodeId), [requirementId, nodeId])
  // 文件 / 输出变化计数：每次重新取到节点数据就 +1，作为 NodeInfoPanel 的刷新信号
  const [filesVersion, setFilesVersion] = useState(0)
  const lastStatus = useRef<string | undefined>(undefined)

  /** 重新拉取需求与节点（输出提交 / 上传输入后调用）。 */
  const refreshAll = useCallback((): void => {
    requirement.reload()
    node.reload()
    setFilesVersion(version => version + 1)
  }, [requirement.reload, node.reload])

  const nodeData: RequirementNodeVO | undefined = node.state.kind === 'ready' ? node.state.data : undefined
  const readonly = nodeData?.readonly !== false || nodeData.status !== 'current'
  const requirementReload = requirement.reload

  const nodeStatus = nodeData?.status
  useEffect(() => {
    if (nodeStatus === undefined) return
    const previous = lastStatus.current
    lastStatus.current = nodeStatus
    if (previous !== 'current' || nodeStatus !== 'done') return
    toast(t('node.info.advanced'))
    requirementReload()
    void api.requirement.detail(requirementId).then(detail => {
      if (!detail.permissions.canOperate) return
      const next = detail.nodes.find(item => item.status === 'current')
      if (next === undefined) return
      // 只推进仍停留在这个节点的页面，并保留用户当前的页签。
      store.update(state => {
        const page = state.stack[state.stack.length - 1]
        if (page?.kind !== 'node' || page.requirementId !== requirementId || page.nodeId !== nodeId) return state
        return { ...state, stack: [...state.stack.slice(0, -1), { ...page, nodeId: next.id }] }
      })
    }).catch(() => undefined)
  }, [api.requirement, nodeStatus, requirementId, nodeId, store, toast, t, requirementReload])

  useEffect(() => {
    if (nodeData === undefined || nodeData.readonly || nodeData.status !== 'current') return
    let disposed = false
    let timer: number | undefined
    const tick = async (): Promise<void> => {
      try {
        const latest = await api.node.detail(requirementId, nodeId)
        if (!disposed && (latest.readonly || latest.status !== 'current')) {
          refreshAll()
          return
        }
      } catch (error) {
        if (!disposed && error instanceof ApiError && (error.status === 403 || error.status === 404)) {
          refreshAll()
          return
        }
      }
      if (!disposed) timer = window.setTimeout(() => { void tick() }, POLL_INTERVAL_MS)
    }
    timer = window.setTimeout(() => { void tick() }, POLL_INTERVAL_MS)
    return () => {
      disposed = true
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [api.node, nodeData, requirementId, nodeId, refreshAll])

  // 节点信息获焦后刷新文件。会话视图靠轮询验证节点，保持在途创建及原生会话引用。
  useEffect(() => {
    if (!readonly && view === 'session') return
    window.addEventListener('focus', refreshAll)
    return () => { window.removeEventListener('focus', refreshAll) }
  }, [readonly, view, refreshAll])

  const req = requirement.state.kind === 'ready' ? requirement.state.data.requirement : undefined
  const tabs = readonly
    ? [{ key: 'info' as NodeView, label: t('node.tab.info') }]
    : [
      { key: 'session' as NodeView, label: t('node.tab.session') },
      { key: 'info' as NodeView, label: t('node.tab.info') },
    ]

  const headerStatus = nodeData === undefined
    ? undefined
    : nodeData.status === 'done'
      ? { label: t('node.info.status.done'), tone: 'success' as const }
      : nodeData.status === 'current'
        ? { label: t('node.info.status.current'), tone: 'info' as const }
        : { label: t('node.info.status.waiting'), tone: 'neutral' as const }

  return (
    <PageShell
      page="node"
      back={{ label: t('node.backToRequirement'), onClick: () => { nav.back() } }}
      title={(req?.title ?? `#${requirementId}`)}
      subtitle={[
        nodeData === undefined ? `#${nodeId}` : nodeData.name,
        req === undefined ? undefined : t('requirements.progress', { current: req.currentSeq, total: req.nodeCount }),
      ].filter((item): item is string => item !== undefined).join(' · ')}
      actions={(
        <>
          {headerStatus === undefined ? null : <StatusBadge testId="node-header-status" label={headerStatus.label} tone={headerStatus.tone} />}
          <button type="button" data-fde-action="refresh" style={buttonBase} onClick={refreshAll}>{t('common.refresh')}</button>
          <button type="button" data-fde-action="back-to-projects" style={buttonBase} onClick={() => { nav.goProjects() }}>
            {t('projects.title')}
          </button>
        </>
      )}
      tabs={<UnderlineTabs testId="node" tabs={tabs} active={readonly ? 'info' : view} onChange={(key) => { nav.switchNodeView(key) }} />}
    >
      {node.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {node.state.kind === 'failed'
        ? <ErrorState message={node.state.message} onRetry={refreshAll} retryLabel={t('common.retry')} />
        : null}
      {nodeData === undefined ? null : (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
          {readonly && nodeData.status === 'current'
            ? null
            : (
              <div style={{ ...muted, flex: '0 0 auto', marginBottom: '8px' }} data-fde-node-hint={readonly ? 'readonly' : 'editable'}>
                {readonly ? t('node.info.notCurrentHint') : t('node.info.currentNodeHint')}
              </div>
            )}
          {/*
            两个视图都挂在一个容器里：只读时只有「节点信息」；
            会话视图需要撑满剩余高度（DSH 会话自己滚动），节点信息视图自己滚动。
          */}
          <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
            {(readonly ? 'info' : view) === 'session'
              ? <NodeSessionPanel requirementId={requirementId} node={nodeData} />
              : (
                <div style={{ overflow: 'auto', paddingBottom: '16px' }}>
                  {/*
                    key 只放 nodeId：切换节点才需要重置内部状态。
                    曾经把 filesVersion 也放进 key，结果每次上传/提交都会把组件整个卸载重建，
                    重建时拿到的是旧数据（reload 还没返回），页面表现为"闪一下、文件没出现"。
                    filesVersion 依然作为 prop 传入，用于组件内部感知"数据已刷新"。
                  */}
                  <NodeInfoPanel
                    key={nodeId}
                    requirementId={requirementId}
                    node={nodeData}
                    filesVersion={filesVersion}
                    onChange={refreshAll}
                  />
                </div>
              )}
          </div>
        </div>
      )}
    </PageShell>
  )
}
