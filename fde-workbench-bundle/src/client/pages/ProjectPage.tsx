/**
 * 项目页：横向四 Tab「需求 / 流程 / 技能 / 成员」（PRD AC-B1.1：切换 Tab 不离开项目）。
 *
 * 本任务（T5-3）负责：
 * - 四个 Tab 的壳与切换（Tab 状态在页面栈里，节点页返回时仍停在原 Tab）；
 * - 需求 Tab（列表 + 新建需求选流程 + 进入需求）与流程 Tab（列表 + 新建 + 进入编辑器）的宿主；
 * - 技能 / 成员 Tab 是**只读列表占位**（T5-4 做完整功能：上传、版本、删除、成员管理）。
 */
import { useNavigation, type AppStore, type ProjectTab } from '../store/app-store.ts'
import { useWorkbench } from '../store/context.tsx'
import { SkillTabPlaceholder, MembersTabPlaceholder } from './project/ReadonlyTabs.tsx'
import { RequirementsTab } from './requirements/RequirementsTab.tsx'
import { WorkflowsTab } from './workflows/WorkflowsTab.tsx'
import { ErrorState, UnderlineTabs } from '../ui/components.tsx'
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
  const detail = useLoadable(() => api.project.detail(projectId), [projectId])

  return (
    <PageShell
      page="project"
      back={{ label: t('projects.title'), onClick: () => { nav.goProjects() } }}
      title={detail.state.kind === 'ready' ? detail.state.data.name : `#${projectId}`}
      subtitle={detail.state.kind === 'ready' && detail.state.data.description !== null ? detail.state.data.description : undefined}
      actions={(
        <button type="button" style={buttonBase} data-fde-action="refresh-tab" onClick={() => {
          // 刷新当前 Tab：切换一次 key 触发子页面重新挂载取数（子页面的 reload 在内部状态里）
          window.dispatchEvent(new CustomEvent('fde-workbench:refresh-tab'))
        }}>
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
      {tab === 'requirements'
        ? <RequirementsTab store={store} projectId={projectId} />
        : tab === 'workflows'
          ? <WorkflowsTab store={store} projectId={projectId} />
          : tab === 'skills'
            ? <SkillTabPlaceholder projectId={projectId} />
            : <MembersTabPlaceholder projectId={projectId} />}
    </PageShell>
  )
}
