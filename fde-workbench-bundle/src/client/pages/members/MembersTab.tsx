import { useState } from 'react'
import { messageOf } from '../../api/client.ts'
import { projectMemberActions } from '../../api/project-management.ts'
import type { MemberVO, ProjectVO } from '../../api/types.ts'
import { useWorkbench } from '../../store/context.tsx'
import { ConfirmDialog, EmptyState, ErrorState, FormDialog, Loading, StatusBadge } from '../../ui/components.tsx'
import { useLoadable } from '../../ui/use-loadable.ts'
import { buttonBase, buttonDanger, buttonPrimary, card, cardHeader, cardRow, muted } from '../../ui/styles.ts'

type MemberAction = { readonly kind: 'add' } | { readonly kind: 'remove' | 'transfer'; readonly member: MemberVO }

/** 成员读取对项目成员开放，写操作要求项目 owner 和成员角色一致。 */
export function MembersTab({ project, currentUserId, onTransfer }: {
  readonly project: ProjectVO
  readonly currentUserId: number | undefined
  readonly onTransfer: (updated: ProjectVO) => void
}) {
  const { api, t, toast } = useWorkbench()
  const members = useLoadable(() => api.member.list(project.id), [project.id])
  const [action, setAction] = useState<MemberAction>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const items = members.state.kind === 'ready' ? members.state.data : []
  const isOwner = project.ownerId === currentUserId && items.some(member => member.userId === currentUserId && member.role === 'owner')

  const open = (next: MemberAction) => {
    setError(undefined)
    setAction(next)
  }
  const cancel = () => { if (!busy) setAction(undefined) }

  const submit = async (username?: string) => {
    if (action === undefined || !isOwner || busy) return
    setBusy(true)
    setError(undefined)
    const actions = projectMemberActions(api.client, project.id)
    try {
      switch (action.kind) {
        case 'add':
          await actions.add(username ?? '')
          toast(t('members.added'), 'success')
          break
        case 'remove':
          await actions.remove(action.member.userId, project.revision)
          toast(t('members.removed'), 'success')
          break
        case 'transfer': {
          const updated = await actions.transfer(action.member.userId, project.revision)
          toast(t('members.transferred'), 'success')
          setAction(undefined)
          // 项目详情重取时会重挂载成员页，用新 owner 和角色控制入口。
          onTransfer(updated)
          return
        }
      }
      setAction(undefined)
      members.reload()
    } catch (failure) {
      setError(messageOf(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={card}>
      <div style={cardHeader}>
        <span>{t('members.heading')}</span>
        {isOwner ? (
          <button type="button" style={buttonPrimary} data-fde-action="add-member" disabled={busy} onClick={() => open({ kind: 'add' })}>
            {t('members.add')}
          </button>
        ) : null}
      </div>
      {members.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {members.state.kind === 'failed' ? (
        <div style={{ padding: '12px' }}><ErrorState message={members.state.message} onRetry={members.reload} retryLabel={t('common.retry')} /></div>
      ) : null}
      {members.state.kind === 'ready' && items.length === 0
        ? <div style={{ padding: '12px' }}><EmptyState title={t('members.empty')} /></div>
        : null}
      {items.length > 0 ? (
        <div data-fde-member-list="">
          {items.map(member => (
            <div key={member.userId} data-fde-member-row={member.userId} style={{ ...cardRow, justifyContent: 'space-between', flexWrap: 'wrap' }}>
              <div style={{ minWidth: 0 }}>
                <span style={{ fontWeight: 600 }}>{member.displayName}</span>
                <span style={{ ...muted, marginLeft: '8px' }}>@{member.username}</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                <StatusBadge label={member.role === 'owner' ? t('members.role.owner') : t('members.role.member')} tone={member.role === 'owner' ? 'info' : 'neutral'} />
                {isOwner && member.role === 'member' && member.userId !== project.ownerId ? (
                  <>
                    <button type="button" style={buttonBase} disabled={busy} data-fde-member-transfer={member.userId} onClick={() => open({ kind: 'transfer', member })}>
                      {t('members.transfer')}
                    </button>
                    <button type="button" style={buttonDanger} disabled={busy} data-fde-member-remove={member.userId} onClick={() => open({ kind: 'remove', member })}>
                      {t('members.remove')}
                    </button>
                  </>
                ) : null}
              </div>
            </div>
          ))}
        </div>
      ) : null}
      {action?.kind === 'add' ? (
        <FormDialog
          title={t('members.add')}
          fields={[{ key: 'username', label: t('members.username'), required: true }]}
          submitLabel={t('members.add')}
          busy={busy}
          error={error}
          onSubmit={values => { void submit(values['username']?.trim()) }}
          onCancel={cancel}
        />
      ) : null}
      {action === undefined || action.kind === 'add' ? null : (
        <ConfirmDialog
          title={t(action.kind === 'remove' ? 'members.remove' : 'members.transfer')}
          body={<>
            <div>{t(action.kind === 'remove' ? 'members.removeBody' : 'members.transferBody', { username: action.member.username })}</div>
            {error === undefined ? null : <div style={{ marginTop: '8px' }}><ErrorState message={error} /></div>}
          </>}
          confirmLabel={t(action.kind === 'remove' ? 'members.remove' : 'members.transfer')}
          danger={action.kind === 'remove'}
          busy={busy}
          onConfirm={() => { void submit() }}
          onCancel={cancel}
        />
      )}
    </div>
  )
}
