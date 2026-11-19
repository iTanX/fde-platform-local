/**
 * 技能 / 成员 Tab 的只读列表占位（T5-3 范围内）。
 *
 * 说明：完整的技能管理（上传、版本、下载、删除引用提示）与成员管理（添加、移除、转让 owner）
 * 属于 T5-4；这里只把列表读出来并展示，让项目四 Tab 在 T5-3 验收时「每个 Tab 都有内容」，
 * 同时明确标注只读来源，避免让人误以为功能已经做完。
 */
import { EmptyState, ErrorState, Loading, StatusBadge } from '../../ui/components.tsx'
import { useLoadable } from '../../ui/use-loadable.ts'
import { useWorkbench } from '../../store/context.tsx'
import { buttonBase, card, cardHeader, cardRow, color, muted } from '../../ui/styles.ts'

/**
 * 技能 Tab（只读）。
 * @param props - 项目 ID。
 */
export function SkillTabPlaceholder({ projectId }: { readonly projectId: number }) {
  const { api, t } = useWorkbench()
  const skills = useLoadable(() => api.skill.list(projectId), [projectId])
  return (
    <div style={card}>
      <div style={cardHeader}>
        <span>{t('skills.heading')}</span>
        <span style={muted}>{t('skills.readonlyHint')}</span>
      </div>
      {skills.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {skills.state.kind === 'failed'
        ? (
          <div style={{ padding: '12px' }}>
            <ErrorState message={skills.state.message} onRetry={skills.reload} retryLabel={t('common.retry')} />
          </div>
        )
        : null}
      {skills.state.kind === 'ready'
        ? (
          skills.state.data.length === 0
            ? <div style={{ padding: '12px' }}><EmptyState title={t('skills.empty')} /></div>
            : (
              <div data-fde-skill-list="">
                {skills.state.data.map(skill => (
                  <div key={skill.id} data-fde-skill-row={skill.id} style={{ ...cardRow, justifyContent: 'space-between' }}>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontWeight: 600 }}>{skill.name}</div>
                      {skill.description === null ? null : <div style={muted}>{skill.description}</div>}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flex: '0 0 auto' }}>
                      <span style={muted}>{t('skills.counts', { count: skill.usageCount })}</span>
                      {skill.currentVersionNo === null
                        ? null
                        : <StatusBadge label={t('skills.version', { version: skill.currentVersionNo })} tone="info" />}
                    </div>
                  </div>
                ))}
              </div>
            )
        )
        : null}
    </div>
  )
}

/**
 * 成员 Tab（只读）。
 * @param props - 项目 ID。
 */
export function MembersTabPlaceholder({ projectId }: { readonly projectId: number }) {
  const { api, t } = useWorkbench()
  const members = useLoadable(() => api.member.list(projectId), [projectId])
  return (
    <div style={card}>
      <div style={cardHeader}>
        <span>{t('members.heading')}</span>
        <span style={muted}>{t('members.readonlyHint')}</span>
      </div>
      {members.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {members.state.kind === 'failed'
        ? (
          <div style={{ padding: '12px' }}>
            <ErrorState message={members.state.message} onRetry={members.reload} retryLabel={t('common.retry')} />
          </div>
        )
        : null}
      {members.state.kind === 'ready'
        ? (
          members.state.data.length === 0
            ? <div style={{ padding: '12px' }}><EmptyState title={t('members.empty')} /></div>
            : (
              <div data-fde-member-list="">
                {members.state.data.map(member => (
                  <div key={member.userId} data-fde-member-row={member.userId} style={{ ...cardRow, justifyContent: 'space-between' }}>
                    <div style={{ minWidth: 0 }}>
                      <span style={{ fontWeight: 600 }}>{member.displayName}</span>
                      <span style={{ ...muted, marginLeft: '8px' }}>@{member.username}</span>
                    </div>
                    <StatusBadge
                      label={member.role === 'owner' ? t('members.role.owner') : t('members.role.member')}
                      tone={member.role === 'owner' ? 'info' : 'neutral'}
                    />
                  </div>
                ))}
              </div>
            )
        )
        : null}
      <div style={{ ...cardRow, borderBottom: 'none', color: color.textMuted, fontSize: '12px' }}>
        <button type="button" style={buttonBase} onClick={members.reload}>{t('common.refresh')}</button>
      </div>
    </div>
  )
}
