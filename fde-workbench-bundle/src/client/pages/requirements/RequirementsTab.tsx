/**
 * 需求 Tab：需求列表 + 新建需求（必须选一个流程）+ 编辑 / 删除 + 进入需求详情。
 *
 * 对应 PRD REQ-F1、REQ-F2、REQ-F3 的列表部分：
 * - AC-F1.1：新建需求时必须选择项目内一个流程，创建后立即生成流程实例（第一节点为当前节点），
 *   创建成功后直接进入需求详情，让用户马上看到节点时间线；
 * - AC-F2.1：流程只能单选，创建后不可更换（编辑对话框里没有流程字段）；
 * - AC-F1.2 / AC-F1.3：编辑与删除只对可编辑者显示（后端 `editable` 字段），删除需二次确认。
 */
import { useState } from 'react'
import { messageOf } from '../../api/client.ts'
import type { RequirementVO, WorkflowSummaryVO } from '../../api/types.ts'
import { useNavigation, type AppStore } from '../../store/app-store.ts'
import { useWorkbench } from '../../store/context.tsx'
import { ConfirmDialog, EmptyState, ErrorState, FormDialog, Loading, StatusBadge } from '../../ui/components.tsx'
import { useLoadable } from '../../ui/use-loadable.ts'
import {
  buttonBase, buttonDanger, buttonPrimary, card, cardHeader, cardRow, color, muted,
} from '../../ui/styles.ts'

/**
 * 需求列表 Tab。
 * @param props - 页面栈 store 与项目 ID。
 */
export function RequirementsTab({ store, projectId }: { readonly store: AppStore; readonly projectId: number }) {
  const { api, t } = useWorkbench()
  const nav = useNavigation(store)
  const needs = useLoadable(() => api.requirement.list(projectId), [projectId])
  const workflows = useLoadable(() => api.workflow.list(projectId), [projectId])
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<RequirementVO | undefined>(undefined)
  const [removing, setRemoving] = useState<RequirementVO | undefined>(undefined)
  const [busy, setBusy] = useState(false)
  const [dialogError, setDialogError] = useState<string | undefined>(undefined)
  // 新建需求时选中的流程 ID（单选，AC-F2.1）。流程列表到达后默认选第一个。
  const [workflowId, setWorkflowId] = useState('')
  const workflowOptions = workflows.state.kind === 'ready' ? workflows.state.data : []
  const selectedWorkflow = workflowId !== '' ? workflowId : (workflowOptions[0] === undefined ? '' : String(workflowOptions[0].id))

  /** 创建需求：body 里带 workflowId（AC-F1.1 / AC-F2.1）。 */
  const create = async (values: Record<string, string>): Promise<void> => {
    const chosenWorkflowId = Number(selectedWorkflow)
    if (!Number.isFinite(chosenWorkflowId) || chosenWorkflowId <= 0) {
      setDialogError(t('requirements.workflowMissing'))
      return
    }
    setBusy(true)
    setDialogError(undefined)
    try {
      const detail = await api.requirement.create(projectId, {
        title: values['title'] ?? '',
        description: values['description'],
        workflowId: chosenWorkflowId,
      })
      setCreating(false)
      needs.reload()
      // 进入需求详情（节点时间线），用户下一步通常是进入当前节点
      nav.openRequirement(detail.requirement.projectId, detail.requirement.id)
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  /** 编辑标题 / 说明（revision 原样回传）。 */
  const save = async (values: Record<string, string>): Promise<void> => {
    if (editing === undefined) return
    setBusy(true)
    setDialogError(undefined)
    try {
      await api.requirement.update(editing.id, {
        title: values['title'] ?? '',
        description: values['description'],
        revision: editing.revision,
      })
      setEditing(undefined)
      needs.reload()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  /** 删除需求（连同节点实例与会话关联）。 */
  const remove = async (): Promise<void> => {
    if (removing === undefined) return
    setBusy(true)
    try {
      await api.requirement.remove(removing.id, removing.revision)
      setRemoving(undefined)
      needs.reload()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  const canCreate = workflowOptions.length > 0

  return (
    <div style={card}>
      <div style={cardHeader}>
        <span>{t('requirements.heading')}{needs.state.kind === 'ready' ? ` · ${t('common.count', { count: needs.state.data.total })}` : ''}</span>
        <button
          type="button"
          data-fde-action="create-requirement"
          style={buttonPrimary}
          onClick={() => { setDialogError(undefined); setCreating(true) }}
        >
          {t('requirements.create')}
        </button>
      </div>

      {needs.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {needs.state.kind === 'failed'
        ? <div style={{ padding: '12px' }}><ErrorState message={needs.state.message} onRetry={needs.reload} retryLabel={t('common.retry')} /></div>
        : null}
      {needs.state.kind === 'ready'
        ? (
          needs.state.data.items.length === 0
            ? (
              <div style={{ padding: '12px' }}>
                <EmptyState title={t('requirements.empty')} hint={t('requirements.emptyHint')} />
              </div>
            )
            : (
              <div data-fde-requirement-list="">
                {needs.state.data.items.map(item => (
                  <RequirementRow
                    key={item.id}
                    requirement={item}
                    onOpen={() => { nav.openRequirement(item.projectId, item.id) }}
                    onEdit={() => { setDialogError(undefined); setEditing(item) }}
                    onRemove={() => { setDialogError(undefined); setRemoving(item) }}
                  />
                ))}
              </div>
            )
        )
        : null}

      {creating
        ? (
          <FormDialog
            title={t('requirements.create')}
            fields={[
              { key: 'title', label: t('requirements.title'), placeholder: t('requirements.titlePlaceholder'), required: true },
              { key: 'description', label: t('requirements.description'), placeholder: t('requirements.descriptionPlaceholder'), multiline: true },
            ]}
            submitLabel={busy ? t('common.creating') : t('common.create')}
            busy={busy}
            canSubmit={canCreate}
            error={dialogError ?? (canCreate ? undefined : t('requirements.workflowMissing'))}
            extra={(
              <div style={{ marginTop: '4px' }}>
                <label style={{ ...muted, display: 'block', marginBottom: '4px' }} htmlFor="fde-field-workflowId">
                  {t('requirements.workflow')} <span style={{ color: color.danger }}>*</span>
                </label>
                <WorkflowSelect
                  options={workflowOptions}
                  value={selectedWorkflow}
                  onChange={setWorkflowId}
                  loading={workflows.state.kind === 'loading'}
                />
                <div style={{ ...muted, marginTop: '4px', lineHeight: 1.6 }}>{t('requirements.workflowHint')}</div>
              </div>
            )}
            onSubmit={(values) => { void create(values) }}
            onCancel={() => { setCreating(false) }}
          />
        )
        : null}

      {editing === undefined ? null : (
        <FormDialog
          title={t('requirements.editTitle')}
          fields={[
            { key: 'title', label: t('requirements.title'), required: true, defaultValue: editing.title },
            { key: 'description', label: t('requirements.description'), multiline: true, defaultValue: editing.description ?? '' },
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
            title={t('requirements.deleteTitle')}
            body={(
              <>
                <div>{t('requirements.deleteBody', { title: removing.title })}</div>
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

/**
 * 流程下拉框（受控组件）。
 *
 * 为什么单独一个控件：流程选项来自接口，而 `FormDialog` 只管理文本字段；
 * 这里把选中值交给父组件的 state（`workflowId`），提交时与表单值一起组装请求体（AC-F2.1：只能单选）。
 * @param props - 流程选项、当前值、变更回调与加载态。
 */
function WorkflowSelect({ options, value, onChange, loading }: {
  readonly options: readonly WorkflowSummaryVO[]
  readonly value: string
  readonly onChange: (value: string) => void
  readonly loading: boolean
}) {
  const { t } = useWorkbench()
  return (
    <select
      aria-label={t('requirements.workflow')}
      data-fde-field="workflowId"
      value={value}
      disabled={loading}
      onChange={(event) => { onChange(event.target.value) }}
      style={{
        border: `1px solid ${color.border}`, borderRadius: '6px', background: 'transparent',
        color: 'inherit', font: 'inherit', fontSize: '13px', padding: '3px 8px', width: '100%',
      }}
    >
      {loading ? <option value="">{t('common.loading')}</option> : null}
      {options.map(option => (
        <option key={option.id} value={String(option.id)}>
          {option.name}（{t('workflows.nodes', { count: option.nodeCount })}）
        </option>
      ))}
    </select>
  )
}

/** 需求列表的一行。 */
function RequirementRow({ requirement, onOpen, onEdit, onRemove }: {
  readonly requirement: RequirementVO
  readonly onOpen: () => void
  readonly onEdit: () => void
  readonly onRemove: () => void
}) {
  const { t } = useWorkbench()
  const completed = requirement.status === 'completed'
  return (
    <div data-fde-requirement-row={requirement.id} style={{ ...cardRow, alignItems: 'flex-start' }}>
      <div style={{ flex: '1 1 auto', minWidth: 0 }}>
        <button
          type="button"
          data-fde-requirement-open={requirement.id}
          onClick={onOpen}
          style={{
            appearance: 'none', border: 'none', background: 'transparent', padding: 0, cursor: 'pointer',
            color: color.accent, font: 'inherit', fontSize: '13px', fontWeight: 600, textAlign: 'left',
          }}
        >
          {requirement.title}
        </button>
        <div style={{ ...muted, marginTop: '2px' }}>
          {requirement.workflowName ?? `#${requirement.workflowId}`}
          {' · '}
          {completed
            ? t('requirements.done', { done: requirement.doneNodeCount, total: requirement.nodeCount })
            : t('requirements.progress', { current: requirement.currentSeq, total: requirement.nodeCount })}
          {' · '}
          {t('requirements.creator', { id: requirement.creatorId })}
          {' · '}
          {requirement.editable ? t('requirements.editable') : t('requirements.readonly')}
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: '0 0 auto' }}>
        <StatusBadge
          label={completed ? t('requirements.status.completed') : t('requirements.status.in_progress')}
          tone={completed ? 'success' : 'info'}
        />
        <button type="button" style={buttonBase} onClick={onOpen}>{t('common.enter')}</button>
        {requirement.editable
          ? (
            <>
              <button type="button" style={buttonBase} data-fde-requirement-edit={requirement.id} onClick={onEdit}>{t('common.edit')}</button>
              <button type="button" style={buttonDanger} data-fde-requirement-delete={requirement.id} onClick={onRemove}>{t('common.delete')}</button>
            </>
          )
          : null}
      </div>
    </div>
  )
}
