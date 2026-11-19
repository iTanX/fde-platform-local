/**
 * 项目中心（PRD REQ-B1~B3、AC-B2.1~B2.3、AC-B3.1）。
 *
 * - 只列出「我所属的项目」（后端已按成员表过滤，前端不再二次过滤）。
 * - 卡片展示：名称、简介、成员数 / 需求数；owner 才能在卡片上看到「编辑」「删除」。
 * - 创建项目：弹表单；创建人自动成为 owner（由后端保证）。
 * - 删除项目：二次确认（AC-B2.3），`revision` 从卡片数据原样回传。
 */
import { useState } from 'react'
import { messageOf } from '../api/client.ts'
import type { ProjectVO } from '../api/types.ts'
import { useAppState, useNavigation, type AppStore } from '../store/app-store.ts'
import { useWorkbench } from '../store/context.tsx'
import {
  ConfirmDialog, EmptyState, ErrorState, FormDialog, Loading,
} from '../ui/components.tsx'
import { PageShell } from '../ui/PageShell.tsx'
import { useLoadable } from '../ui/use-loadable.ts'
import { buttonBase, buttonDanger, buttonPrimary, card, cardRow, color, muted } from '../ui/styles.ts'

/**
 * 项目中心页面。
 * @param props - 页面栈 store（由 `WorkbenchMain` 传入，保证与其它页面共享同一实例）。
 */
export function ProjectsPage({ store }: { readonly store: AppStore }) {
  const { api, t, currentUsername } = useWorkbench()
  const state = useAppState(store)
  const nav = useNavigation(store)
  const projects = useLoadable(() => api.project.list(), [])
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<ProjectVO | undefined>(undefined)
  const [removing, setRemoving] = useState<ProjectVO | undefined>(undefined)
  const [busy, setBusy] = useState(false)
  const [dialogError, setDialogError] = useState<string | undefined>(undefined)

  /** 进入项目（后续页面都从当前项目缓存取上下文）。 */
  const enter = (project: ProjectVO): void => { nav.openProject(project) }

  /** 创建项目。 */
  const create = async (values: Record<string, string>): Promise<void> => {
    setBusy(true)
    setDialogError(undefined)
    try {
      const project = await api.project.create({ name: values['name'] ?? '', description: values['description'] })
      setCreating(false)
      projects.reload()
      // 创建后直接进入，省掉一次点击（AC-B2.1：创建人自动成为 owner 与成员）
      enter(project)
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  /** 编辑项目名称 / 简介。 */
  const save = async (values: Record<string, string>): Promise<void> => {
    if (editing === undefined) return
    setBusy(true)
    setDialogError(undefined)
    try {
      await api.project.update(editing.id, {
        name: values['name'] ?? '',
        description: values['description'],
        revision: editing.revision,
      })
      setEditing(undefined)
      projects.reload()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  /** 删除项目（逻辑删除）。 */
  const remove = async (): Promise<void> => {
    if (removing === undefined) return
    setBusy(true)
    try {
      await api.project.remove(removing.id, removing.revision)
      setRemoving(undefined)
      projects.reload()
    } catch (error) {
      setDialogError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageShell
      page="projects"
      title={t('projects.title')}
      subtitle={currentUsername === undefined ? t('projects.subtitle') : `${currentUsername} · ${t('projects.subtitle')}`}
      actions={(
        <>
          <button
            type="button"
            data-fde-action="refresh"
            onClick={projects.reload}
            style={buttonBase}
          >
            {t('common.refresh')}
          </button>
          <button
            type="button"
            data-fde-action="create-project"
            onClick={() => { setDialogError(undefined); setCreating(true) }}
            style={buttonPrimary}
          >
            {t('projects.create')}
          </button>
          <button
            type="button"
            data-fde-action="logout"
            onClick={() => { store.signOut() }}
            style={buttonBase}
          >
            {t('common.logout')}
          </button>
        </>
      )}
    >
      {projects.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {projects.state.kind === 'failed'
        ? (
          <ErrorState
            message={projects.state.unreachable
              ? t('common.backendUnreachable', { base: api.client.baseUrl })
              : projects.state.message}
            hint={projects.state.unreachable ? projects.state.message : undefined}
            onRetry={projects.reload}
            retryLabel={t('common.retry')}
          />
        )
        : null}
      {projects.state.kind === 'ready'
        ? (
          projects.state.data.items.length === 0
            ? (
              <EmptyState
                title={t('projects.empty')}
                hint={t('projects.emptyHint')}
                action={(
                  <button
                    type="button"
                    style={buttonPrimary}
                    onClick={() => { setDialogError(undefined); setCreating(true) }}
                  >
                    {t('projects.create')}
                  </button>
                )}
              />
            )
            : (
              <div
                data-fde-project-list=""
                style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '12px' }}
              >
                {projects.state.data.items.map(project => (
                  <ProjectCard
                    key={project.id}
                    project={project}
                    isOwner={project.ownerId === state.user?.id}
                    onEnter={() => { enter(project) }}
                    onEdit={() => { setDialogError(undefined); setEditing(project) }}
                    onRemove={() => { setDialogError(undefined); setRemoving(project) }}
                  />
                ))}
              </div>
            )
        )
        : null}

      {creating
        ? (
          <FormDialog
            title={t('projects.createTitle')}
            fields={[
              { key: 'name', label: t('projects.name'), placeholder: t('projects.namePlaceholder'), required: true },
              { key: 'description', label: t('projects.description'), placeholder: t('projects.descriptionPlaceholder'), multiline: true },
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
          title={t('projects.editTitle')}
          fields={[
            { key: 'name', label: t('projects.name'), required: true, defaultValue: editing.name },
            { key: 'description', label: t('projects.description'), multiline: true, defaultValue: editing.description ?? '' },
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
            title={t('projects.deleteConfirmTitle')}
            body={(
              <>
                <div>{t('projects.deleteConfirmBody', { name: removing.name })}</div>
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

/** 项目卡片。 */
function ProjectCard({ project, isOwner, onEnter, onEdit, onRemove }: {
  readonly project: ProjectVO
  readonly isOwner: boolean
  readonly onEnter: () => void
  readonly onEdit: () => void
  readonly onRemove: () => void
}) {
  const { t } = useWorkbench()
  return (
    <div data-fde-project-card={project.id} style={{ ...card, display: 'flex', flexDirection: 'column' }}>
      <div style={{ ...cardRow, flexDirection: 'column', alignItems: 'stretch', gap: '2px', padding: '10px 12px', flex: '1 1 auto' }}>
        <button
          type="button"
          onClick={onEnter}
          data-fde-project-enter={project.id}
          style={{
            appearance: 'none', border: 'none', background: 'transparent', padding: 0, cursor: 'pointer',
            color: color.accent, font: 'inherit', fontSize: '14px', fontWeight: 600, textAlign: 'left',
          }}
        >
          {project.name}
        </button>
        <div style={{ ...muted, marginTop: '2px' }}>
          {t('projects.summary', { members: project.memberCount, requirements: project.requirementCount })}
        </div>
        {project.description === null || project.description === '' ? null : (
          <div style={{ fontSize: '13px', marginTop: '2px', color: color.textMuted }}>{project.description}</div>
        )}
      </div>
      <div style={{ ...cardRow, justifyContent: 'flex-end', borderBottom: 'none', paddingTop: '6px', paddingBottom: '6px' }}>
        <button type="button" style={buttonPrimary} data-fde-enter={project.id} onClick={onEnter}>{t('common.enter')}</button>
        {isOwner
          ? (
            <>
              <button type="button" style={buttonBase} data-fde-project-edit={project.id} onClick={onEdit}>{t('common.edit')}</button>
              <button type="button" style={buttonDanger} data-fde-project-delete={project.id} onClick={onRemove}>{t('common.delete')}</button>
            </>
          )
          : null}
      </div>
    </div>
  )
}
