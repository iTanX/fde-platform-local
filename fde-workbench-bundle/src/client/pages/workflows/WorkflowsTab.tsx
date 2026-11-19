/**
 * 流程 Tab：流程列表 + 新建 / 编辑 / 删除 + 进入节点编辑器（PRD REQ-E1）。
 *
 * 交互与约束：
 * - 流程 `revision` 是 `updated_at` 的**毫秒时间戳**，前端只当作不透明数字原样回传（不展示、不 +1）；
 * - 被需求引用的流程删除会返回 409/10403，错误文案直接展示后端 message；
 * - 「编辑节点」进入 `WorkflowEditorPage`，保存时走 `PUT /api/workflows/{id}/nodes` 整体覆盖。
 */
import { useState } from 'react'
import { messageOf } from '../../api/client.ts'
import type { WorkflowSummaryVO } from '../../api/types.ts'
import { useAppState, useNavigation, type AppStore } from '../../store/app-store.ts'
import { useWorkbench } from '../../store/context.tsx'
import { ConfirmDialog, EmptyState, ErrorState, FormDialog, Loading } from '../../ui/components.tsx'
import { useLoadable } from '../../ui/use-loadable.ts'
import { buttonBase, buttonDanger, buttonPrimary, card, cardHeader, cardRow, color, muted } from '../../ui/styles.ts'

/**
 * 流程列表 Tab。
 * @param props - 页面栈 store 与项目 ID。
 */
export function WorkflowsTab({ store, projectId, isOwner }: {
  readonly store: AppStore
  readonly projectId: number
  readonly isOwner: boolean
}) {
  const { api, t } = useWorkbench()
  const nav = useNavigation(store)
  const currentUserId = useAppState(store).user?.id
  // 项目成员都能创建流程。现有流程只有创建人和项目 owner 可修改。
  const canEdit = (workflow: WorkflowSummaryVO) => isOwner || workflow.createdBy === currentUserId
  const workflows = useLoadable(() => api.workflow.list(projectId), [projectId])
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<WorkflowSummaryVO | undefined>(undefined)
  const [removing, setRemoving] = useState<WorkflowSummaryVO | undefined>(undefined)
  const [busy, setBusy] = useState(false)
  const [dialogError, setDialogError] = useState<string | undefined>(undefined)

  /** 创建流程（只建壳，节点在编辑器里编排）。 */
  const create = async (values: Record<string, string>): Promise<void> => {
    setBusy(true)
    setDialogError(undefined)
    try {
      const created = await api.workflow.create(projectId, {
        name: values['name'] ?? '',
        description: values['description'],
      })
      setCreating(false)
      workflows.reload()
      // 新建流程没有节点，直接进编辑器让用户开始编排
      nav.openWorkflow(projectId, created.id)
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  /** 重命名 / 改说明（revision 原样回传）。 */
  const save = async (values: Record<string, string>): Promise<void> => {
    if (editing === undefined) return
    setBusy(true)
    setDialogError(undefined)
    try {
      await api.workflow.update(editing.id, {
        name: values['name'] ?? '',
        description: values['description'],
        revision: editing.revision,
      })
      setEditing(undefined)
      workflows.reload()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  /** 删除流程（被需求引用时后端拒绝）。 */
  const remove = async (): Promise<void> => {
    if (removing === undefined) return
    setBusy(true)
    try {
      await api.workflow.remove(removing.id, removing.revision)
      setRemoving(undefined)
      workflows.reload()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={card}>
      <div style={cardHeader}>
        <span>{t('workflows.heading')}{workflows.state.kind === 'ready' ? ` · ${t('common.count', { count: workflows.state.data.length })}` : ''}</span>
        <button
          type="button"
          data-fde-action="create-workflow"
          style={buttonPrimary}
          onClick={() => { setDialogError(undefined); setCreating(true) }}
        >
          {t('workflows.create')}
        </button>
      </div>

      {workflows.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {workflows.state.kind === 'failed'
        ? <div style={{ padding: '12px' }}><ErrorState message={workflows.state.message} onRetry={workflows.reload} retryLabel={t('common.retry')} /></div>
        : null}
      {workflows.state.kind === 'ready'
        ? (
          workflows.state.data.length === 0
            ? <div style={{ padding: '12px' }}><EmptyState title={t('workflows.empty')} hint={t('workflows.emptyHint')} /></div>
            : (
              <div data-fde-workflow-list="">
                {workflows.state.data.map(item => (
                  <div key={item.id} data-fde-workflow-row={item.id} style={{ ...cardRow, alignItems: 'flex-start' }}>
                    <div style={{ flex: '1 1 auto', minWidth: 0 }}>
                      <button
                        type="button"
                        data-fde-workflow-open={item.id}
                        onClick={() => { nav.openWorkflow(projectId, item.id) }}
                        style={{
                          appearance: 'none', border: 'none', background: 'transparent', padding: 0, cursor: 'pointer',
                          color: color.accent, font: 'inherit', fontSize: '13px', fontWeight: 600, textAlign: 'left',
                        }}
                      >
                        {item.name}
                      </button>
                      <div style={{ ...muted, marginTop: '2px' }}>
                        {t('workflows.nodes', { count: item.nodeCount })}
                        {item.description === null ? null : ` · ${item.description}`}
                      </div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: '0 0 auto' }}>
                      <button type="button" style={buttonBase} onClick={() => { nav.openWorkflow(projectId, item.id) }}>
                        {t(canEdit(item) ? 'workflows.openEditor' : 'workflows.view')}
                      </button>
                      {canEdit(item) ? (
                        <>
                          <button type="button" style={buttonBase} data-fde-workflow-edit={item.id} onClick={() => { setDialogError(undefined); setEditing(item) }}>
                            {t('common.edit')}
                          </button>
                          <button type="button" style={buttonDanger} data-fde-workflow-delete={item.id} onClick={() => { setDialogError(undefined); setRemoving(item) }}>
                            {t('common.delete')}
                          </button>
                        </>
                      ) : null}
                    </div>
                  </div>
                ))}
              </div>
            )
        )
        : null}

      {creating
        ? (
          <FormDialog
            title={t('workflows.create')}
            fields={[
              { key: 'name', label: t('workflows.name'), placeholder: t('workflows.namePlaceholder'), required: true },
              { key: 'description', label: t('workflows.description'), placeholder: t('workflows.descriptionPlaceholder'), multiline: true },
            ]}
            submitLabel={busy ? t('common.creating') : t('common.create')}
            busy={busy}
            error={dialogError}
            onSubmit={(values) => { void create(values) }}
            onCancel={() => { setCreating(false) }}
          />
        )
        : null}

      {editing === undefined ? null : (
        <FormDialog
          title={t('workflows.renameTitle')}
          fields={[
            { key: 'name', label: t('workflows.name'), required: true, defaultValue: editing.name },
            { key: 'description', label: t('workflows.description'), multiline: true, defaultValue: editing.description ?? '' },
          ]}
          submitLabel={t('common.save')}
          busy={busy}
          error={dialogError}
          onSubmit={(values) => { void save(values) }}
          onCancel={() => { setEditing(undefined) }}
        />
      )}

      {removing === undefined
        ? null
        : (
          <ConfirmDialog
            title={t('workflows.deleteTitle')}
            body={(
              <>
                <div>{t('workflows.deleteBody', { name: removing.name })}</div>
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
    </div>
  )
}
