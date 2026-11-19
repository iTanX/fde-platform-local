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
 * 自动刷新（AC-F4.6）：节点信息视图在「未只读」时轮询 `advance-preview`；
 * 一旦 `ready=true`（全部必填输出项齐备）或节点状态不再是 `current`，就重新拉节点信息，
 * 让页面出现"已进入下一节点"的提示 / 只读态。切回页签、窗口重新获焦也会再拉一次。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
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
  const readonly = nodeData?.readonly === true
  const requirementReload = requirement.reload

  // 节点状态变化（未被轮询捕到的推进）时提示一次，并刷新需求进度
  useEffect(() => {
    if (nodeData === undefined) return
    const previous = lastStatus.current
    lastStatus.current = nodeData.status
    if (previous !== undefined && previous !== nodeData.status) {
      toast(t('node.info.staleRefreshed'))
      requirementReload()
    }
  }, [nodeData?.status, toast, t, requirementReload])

  // 轮询推进条件：只看「未只读」的节点（只读节点不会变化）
  useEffect(() => {
    if (nodeData === undefined || nodeData.readonly) return
    let disposed = false
    let timer: number | undefined
    /** 查一次推进条件，ready 则刷新。 */
    const tick = async (): Promise<void> => {
      try {
        const preview = await api.node.advancePreview(requirementId, nodeId)
        if (!disposed && preview.ready) {
          toast(t('node.info.advanced'))
          refreshAll()
          return
        }
      } catch {
        // 预览失败不打扰用户（例如网络抖动），下一轮继续
      }
      if (!disposed) timer = window.setTimeout(() => { void tick() }, POLL_INTERVAL_MS)
    }
    timer = window.setTimeout(() => { void tick() }, POLL_INTERVAL_MS)
    return () => {
      disposed = true
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [api.node, nodeData, requirementId, nodeId, refreshAll, toast, t])

  // 切回本页签 / 窗口重新获焦时重取（用户从对话回来后通常需要看到最新状态）
  useEffect(() => {
    /** 重新获焦时刷新节点。 */
    const onFocus = (): void => { refreshAll() }
    window.addEventListener('focus', onFocus)
    return () => { window.removeEventListener('focus', onFocus) }
  }, [refreshAll])

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
              ? (req === undefined
                ? (
                  <div style={{ paddingTop: '8px' }}>
                    {requirement.state.kind === 'failed'
                      ? <ErrorState message={requirement.state.message} onRetry={requirementReload} retryLabel={t('common.retry')} />
                      : <Loading label={t('common.loading')} />}
                  </div>
                )
                : (
                  <NodeSessionPanel
                    requirement={req}
                    defaultSessionId={nodeData.defaultSessionId}
                    readonly={readonly}
                  />
                ))
              
              : (
                <div style={{ overflow: 'auto', paddingBottom: '16px' }}>
                  <NodeInfoPanel
                    key={`${nodeId}-${filesVersion}`}
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
