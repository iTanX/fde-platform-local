/** 项目页四个页签共享项目详情与权限状态。 */
import { useState } from 'react'
import type { ProjectVO } from '../api/types.ts'
import { useAppState, useNavigation, type AppStore, type ProjectTab } from '../store/app-store.ts'
import { useWorkbench } from '../store/context.tsx'
import { MembersTab } from './members/MembersTab.tsx'
import { SkillsTab } from './project/SkillsTab.tsx'
import { RequirementsTab } from './requirements/RequirementsTab.tsx'
import { WorkflowsTab } from './workflows/WorkflowsTab.tsx'
import { ErrorState, Loading, UnderlineTabs } from '../ui/components.tsx'
import { PageShell } from '../ui/PageShell.tsx'
import { useLoadable } from '../ui/use-loadable.ts'
import { buttonBase } from '../ui/styles.ts'

/** 页签定义（顺序即 PRD 的顺序）。 */
const TABS: readonly { key: ProjectTab; labelKey: 'project.tab.requirements' | 'project.tab.workflows' | 'project.tab.skills' | 'project.tab.members' }[] = [
  { key: 'requirements', labelKey: 'project.tab.requirements' },
  { key: 'workflows', labelKey: 'project.tab.workflows' },
  { key: 'skills', labelKey: 'project.tab.skills' },
  { key: 'members', labelKey: 'project.tab.members' },
]

/**
 * 项目页。
 * @param props - 项目 ID、当前 Tab 与页面栈 store。
 */
export function ProjectPage({ projectId, tab, store }: {
  readonly projectId: number
  readonly tab: ProjectTab
  readonly store: AppStore
}) {
  const { api, t } = useWorkbench()
  const nav = useNavigation(store)
  const currentUserId = useAppState(store).user?.id
  const detail = useLoadable(() => api.project.detail(projectId), [projectId])
  const [refreshKey, setRefreshKey] = useState(0)
  const project = detail.state.kind === 'ready' ? detail.state.data : undefined
  const isOwner = project !== undefined && project.ownerId === currentUserId

  const refresh = () => {
    detail.reload()
    setRefreshKey(key => key + 1)
  }

  const onTransfer = (updated: ProjectVO) => {
    // 转让改了 owner 和 revision，也更新本项目的导航缓存。
    store.update(state => state.project?.id === updated.id ? { ...state, project: updated } : state)
    detail.reload()
  }

  return (
    <PageShell
      page="project"
      back={{ label: t('projects.title'), onClick: () => { nav.goProjects() } }}
      title={project?.name ?? `#${projectId}`}
      subtitle={project?.description ?? undefined}
      actions={(
        <button type="button" style={buttonBase} data-fde-action="refresh-tab" onClick={refresh}>
          {t('common.refresh')}
        </button>
      )}
      tabs={(
        <UnderlineTabs
          testId="project"
          tabs={TABS.map(item => ({ key: item.key, label: t(item.labelKey) }))}
          active={tab}
          onChange={(key) => { nav.replaceTab(key) }}
        />
      )}
    >
      {detail.state.kind === 'failed'
        ? (
          <div style={{ marginBottom: '12px' }}>
            <ErrorState message={detail.state.message} onRetry={detail.reload} retryLabel={t('common.retry')} />
          </div>
        )
        : null}
      {detail.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {project === undefined ? null : (
        <div key={`${projectId}:${refreshKey}`}>
          {tab === 'requirements'
            ? <RequirementsTab store={store} projectId={projectId} />
            : tab === 'workflows'
              ? <WorkflowsTab store={store} projectId={projectId} isOwner={isOwner} />
              : tab === 'skills'
                ? <SkillsTab projectId={projectId} currentUserId={currentUserId} isOwner={isOwner} />
                : <MembersTab project={project} currentUserId={currentUserId} onTransfer={onTransfer} />}
        </div>
      )}
    </PageShell>
  )
}
