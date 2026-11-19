/**
 * 需求详情页：需求本体 + 节点时间线（PRD REQ-F3、AC-F3.1）。
 *
 * - 节点列表按 seq 展示每个节点的状态（已完成 / 进行中 / 未开始）并高亮当前节点；
 * - 点击任一节点进入节点页（只读节点同样可进入，页面内只读，见 AC-F5.1）；
 * - 可编辑者（后端 `editable`）可以编辑标题 / 说明与删除需求。
 */
import { useState } from 'react'
import { messageOf } from '../api/client.ts'
import type { NodeProgressVO, RequirementDetailVO } from '../api/types.ts'
import { useNavigation, type AppStore } from '../store/app-store.ts'
import { useWorkbench } from '../store/context.tsx'
import { ConfirmDialog, EmptyState, ErrorState, FormDialog, Loading, StatusBadge } from '../ui/components.tsx'
import { PageShell } from '../ui/PageShell.tsx'
import { useLoadable } from '../ui/use-loadable.ts'
import { buttonBase, buttonDanger, buttonPrimary, card, cardHeader, cardRow, color, muted } from '../ui/styles.ts'

/**
 * 需求详情页。
 * @param props - 页面栈 store、项目 ID 与需求 ID。
 */
export function RequirementDetailPage({ store, projectId, requirementId }: {
  readonly store: AppStore
  readonly projectId: number
  readonly requirementId: number
}) {
  const { api, t } = useWorkbench()
  const nav = useNavigation(store)
  const detail = useLoadable(() => api.requirement.detail(requirementId), [requirementId])
  const [editing, setEditing] = useState(false)
  const [removing, setRemoving] = useState<RequirementDetailVO | undefined>(undefined)
  const [busy, setBusy] = useState(false)
  const [dialogError, setDialogError] = useState<string | undefined>(undefined)

  /** 编辑标题 / 说明。 */
  const save = async (values: Record<string, string>): Promise<void> => {
    if (detail.state.kind !== 'ready') return
    setBusy(true)
    setDialogError(undefined)
    try {
      await api.requirement.update(requirementId, {
        title: values['title'] ?? '',
        description: values['description'],
        revision: detail.state.data.requirement.revision,
      })
      setEditing(false)
      detail.reload()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  /** 删除需求后回到项目需求列表。 */
  const remove = async (): Promise<void> => {
    if (removing === undefined) return
    setBusy(true)
    try {
      await api.requirement.remove(requirementId, removing.requirement.revision)
      setRemoving(undefined)
      nav.back()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  const loaded = detail.state.kind === 'ready' ? detail.state.data : undefined
  const requirement = loaded?.requirement

  return (
    <PageShell
      page="requirement"
      back={{ label: t('requirement.back'), onClick: () => { nav.back() } }}
      title={requirement?.title ?? `#${requirementId}`}
      subtitle={requirement === undefined
        ? undefined
        : [
          `${t('requirement.workflow')}：${requirement.workflowName ?? `#${requirement.workflowId}`}`,
          t('requirements.progress', { current: requirement.currentSeq, total: requirement.nodeCount }),
        ].join(' · ')}
      actions={(
        <>
          <button type="button" data-fde-action="refresh" style={buttonBase} onClick={detail.reload}>{t('common.refresh')}</button>
          {requirement?.editable === true
            ? (
              <>
                <button type="button" data-fde-action="edit-requirement" style={buttonBase} onClick={() => { setDialogError(undefined); setEditing(true) }}>
                  {t('common.edit')}
                </button>
                <button type="button" data-fde-action="delete-requirement" style={buttonDanger} onClick={() => { setDialogError(undefined); setRemoving(loaded) }}>
                  {t('common.delete')}
                </button>
              </>
            )
            : null}
        </>
      )}
    >
      {detail.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {detail.state.kind === 'failed'
        ? <ErrorState message={detail.state.message} onRetry={detail.reload} retryLabel={t('common.retry')} />
        : null}
      {loaded === undefined || requirement === undefined ? null : (
        <>
          <div style={{ ...card, marginBottom: '16px' }}>
            <div style={cardHeader}>
              <span>{t('requirement.nodes')}</span>
              <StatusBadge
                testId="requirement-status"
                label={requirement.status === 'completed' ? t('requirements.status.completed') : t('requirements.status.in_progress')}
                tone={requirement.status === 'completed' ? 'success' : 'info'}
              />
            </div>
            {loaded.nodes.length === 0
              ? <div style={{ padding: '12px' }}><EmptyState title={t('common.empty')} /></div>
              : (
                <div data-fde-node-timeline="">
                  {loaded.nodes.map(node => (
                    <NodeTimelineRow
                      key={node.id}
                      node={node}
                      isCurrent={node.status === 'current'}
                      onOpen={() => { nav.openNode(projectId, requirementId, node.id, 'session') }}
                    />
                  ))}
                </div>
              )}
            <div style={{ ...cardRow, borderBottom: 'none' }}>
              <span style={muted}>
                {requirement.status === 'completed'
                  ? t('requirement.completedHint')
                  : loaded.permissions.canOperate ? '' : t('requirement.readonlyHint')}
              </span>
            </div>
          </div>

          <div style={card}>
            <div style={cardHeader}><span>{t('node.info.description')}</span></div>
            <div style={{ padding: '10px 12px', fontSize: '13px', lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>
              {requirement.description === null || requirement.description === ''
                ? <span style={muted}>{t('node.info.descriptionEmpty')}</span>
                : requirement.description}
            </div>
          </div>
        </>
      )}

      {editing && requirement !== undefined
        ? (
          <FormDialog
            title={t('requirements.editTitle')}
            fields={[
              { key: 'title', label: t('requirements.title'), required: true, defaultValue: requirement.title },
              { key: 'description', label: t('requirements.description'), multiline: true, defaultValue: requirement.description ?? '' },
            ]}
            submitLabel={t('common.save')}
            busy={busy}
            error={dialogError}
            onSubmit={(values) => { void save(values) }}
            onCancel={() => { setEditing(false) }}
          />
        )
        : null}

      {removing === undefined
        ? null
        : (
          <ConfirmDialog
            title={t('requirements.deleteTitle')}
            body={(
              <>
                <div>{t('requirements.deleteBody', { title: removing.requirement.title })}</div>
                {dialogError === undefined ? null : <div style={{ marginTop: '10px' }}><ErrorState message={dialogError} /></div>}
              </>
            )}
            confirmLabel={t('common.delete')}
            danger
            busy={busy}
            onConfirm={() => { void remove() }}
            onCancel={() => { setRemoving(undefined) }}
          />
        )}
    </PageShell>
  )
}

/** 时间线的一行：序号 + 节点名 + 状态 + 进入。 */
function NodeTimelineRow({ node, isCurrent, onOpen }: {
  readonly node: NodeProgressVO
  readonly isCurrent: boolean
  readonly onOpen: () => void
}) {
  const { t } = useWorkbench()
  const tone = node.status === 'done' ? 'success' : node.status === 'current' ? 'info' : 'neutral'
  const label = node.status === 'done'
    ? t('requirement.nodeStatus.done')
    : node.status === 'current' ? t('requirement.nodeStatus.current') : t('requirement.nodeStatus.waiting')
  return (
    <div
      data-fde-node-row={node.id}
      data-fde-node-status={node.status}
      style={{
        ...cardRow,
        // 当前节点整行加浅背景，与 GitHub 列表选中态一致（只改背景令牌，不写死颜色）
        background: isCurrent ? color.bgSubtle : 'transparent',
      }}
    >
      <span style={{ ...muted, width: '20px', flex: '0 0 auto', textAlign: 'right' }}>{node.seq}</span>
      <button
        type="button"
        data-fde-node-open={node.id}
        onClick={onOpen}
        style={{
          appearance: 'none', border: 'none', background: 'transparent', padding: 0, cursor: 'pointer',
          color: isCurrent ? color.text : color.accent, font: 'inherit', fontSize: '13px',
          fontWeight: isCurrent ? 600 : 400, textAlign: 'left', flex: '1 1 auto', minWidth: 0,
        }}
      >
        {node.name}
        {isCurrent ? <span style={{ ...muted, marginLeft: '8px' }}>{t('requirement.currentNode')}</span> : null}
      </button>
      <StatusBadge label={label} tone={tone} testId={`node-status-${node.id}`} />
      <button type="button" style={buttonPrimary} data-fde-node-enter={node.id} onClick={onOpen}>
        {t('requirement.enterNode')}
      </button>
    </div>
  )
}
