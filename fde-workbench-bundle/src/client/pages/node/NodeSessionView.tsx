/** 节点会话列表与选择。只有业务侧已登记的本需求会话可进入嵌入区域。 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, messageOf } from '../../api/client.ts'
import type { RequirementNodeVO, SessionVO } from '../../api/types.ts'
import { useWorkbench } from '../../store/context.tsx'
import { NodeSessionView as EmbeddedNodeSessionView } from '../../session/NodeSessionView.tsx'
import { ErrorState, StatusBadge } from '../../ui/components.tsx'
import { dshSessionTitles, mergeSessionChoices } from '../../ui/session-bridge.ts'
import { buttonBase, buttonDisabled, buttonPrimary, color, muted } from '../../ui/styles.ts'
import { formatTime } from './NodeInfoPanel.tsx'

export function NodeSessionPanel({ requirementId, node }: {
  readonly requirementId: number
  readonly node: RequirementNodeVO
}) {
  const { api, t, sessionHost, renderSlot } = useWorkbench()
  const [sessions, setSessions] = useState<SessionVO[]>()
  const [listError, setListError] = useState<string>()
  const [selected, setSelected] = useState<string>()
  const [operation, setOperation] = useState<'create' | 'switch'>()
  const [actionError, setActionError] = useState<{ kind: 'create' | 'switch'; message: string }>()
  const pendingRegistration = useRef<string | undefined>(undefined)
  const listRequest = useRef(0)
  const workspacePath = node.workspacePath
  const canOperate = !node.readonly && typeof workspacePath === 'string' && workspacePath.trim() !== ''

  const loadSessions = useCallback(async (): Promise<void> => {
    const request = ++listRequest.current
    try {
      const [list] = await Promise.all([
        api.session.list(requirementId),
        sessionHost.sessions.refresh().catch(() => undefined),
      ])
      if (request !== listRequest.current) return
      setSessions(list)
      setListError(undefined)
    } catch (error) {
      if (request === listRequest.current) setListError(messageOf(error))
    }
  }, [api.session, requirementId, sessionHost])

  useEffect(() => {
    if (canOperate) void loadSessions()
    return () => { listRequest.current++ }
  }, [canOperate, loadSessions])

  const choices = mergeSessionChoices(sessions ?? [], dshSessionTitles(sessionHost), selected ?? node.defaultSessionId)
  const current = choices.find(choice => choice.dshSessionId === selected)
    ?? choices.find(choice => choice.dshSessionId === node.defaultSessionId)
    ?? choices[0]

  /** 已确认的后端写入作废在途列表读取，避免旧快照覆盖刚选择的会话。 */
  const activate = (session: SessionVO): void => {
    listRequest.current++
    setSessions(list => [session, ...(list ?? []).filter(item => item.id !== session.id)])
    setListError(undefined)
    setSelected(session.dshSessionId)
  }

  const switchTo = async (session: (typeof choices)[number]): Promise<void> => {
    if (!canOperate || operation !== undefined) return
    setOperation('switch')
    setActionError(undefined)
    try {
      await sessionHost.resolveSession(workspacePath, session.dshSessionId)
      // 先保存节点默认，再显示会话。后端推进可以继承同一个选择。
      activate(await api.session.touch(session.businessId, node.id))
    } catch (error) {
      setActionError({ kind: 'switch', message: t('session.switchFailed', { message: messageOf(error) }) })
    } finally {
      setOperation(undefined)
    }
  }

  const createSession = async (): Promise<void> => {
    if (!canOperate || operation !== undefined) return
    setOperation('create')
    setActionError(undefined)
    try {
      // 登记响应丢失时复用同一次创建的 ID，避免再留一个空白 DSH 会话。
      const dshSessionId = pendingRegistration.current ?? await sessionHost.createSession(workspacePath)
      pendingRegistration.current = dshSessionId
      let registered: SessionVO
      try {
        registered = await api.session.create(requirementId, { dshSessionId, nodeId: node.id })
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 409 || error.code !== 10401) throw error
        const matching = (await api.session.list(requirementId)).find(item => item.dshSessionId === dshSessionId)
        if (matching === undefined) throw error
        // 对账后重新保存默认，防止另一客户端已在两次请求之间切换会话。
        registered = await api.session.touch(matching.id, node.id)
      }
      pendingRegistration.current = undefined
      activate(registered)
    } catch (error) {
      const key = pendingRegistration.current === undefined ? 'session.createFailed' : 'session.bindFailed'
      setActionError({ kind: 'create', message: t(key, { message: messageOf(error) }) })
    } finally {
      setOperation(undefined)
    }
  }

  if (node.readonly) return null
  if (!canOperate) return <ErrorState message={t('session.workspaceUnavailable')} />

  return (
    <div data-fde-view="session" style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: '8px', padding: '6px 0 8px',
        borderBottom: `1px solid ${color.border}`, flex: '0 0 auto', flexWrap: 'wrap',
      }}>
        <span style={{ ...muted, flex: '0 0 auto' }}>{t('session.requirementSessions')}</span>
        <select
          aria-label={t('session.switch')}
          data-fde-session-switch=""
          value={current?.dshSessionId ?? ''}
          onChange={(event) => {
            const choice = choices.find(item => item.dshSessionId === event.target.value)
            if (choice !== undefined) void switchTo(choice)
          }}
          disabled={choices.length === 0 || operation !== undefined}
          style={{
            border: `1px solid ${color.border}`, borderRadius: '6px', background: 'transparent', color: 'inherit',
            font: 'inherit', fontSize: '12px', padding: '2px 8px', maxWidth: '380px', minWidth: '180px',
          }}
        >
          {choices.length === 0 ? <option value="">{t('session.noSession')}</option> : null}
          {choices.map(choice => (
            <option key={choice.dshSessionId} value={choice.dshSessionId}>
              {choice.title}
              {choice.isNodePrimary ? ` · ${t('session.default')}` : ''}
              {choice.lastActiveAt === undefined ? '' : ` · ${t('session.lastActive', { time: formatTime(choice.lastActiveAt) })}`}
            </option>
          ))}
        </select>
        {choices.length === 0 ? null : (
          <StatusBadge label={t('session.count', { count: choices.length })} tone="neutral" testId="session-count" />
        )}
        <span style={{ flex: '1 1 auto' }} />
        <button
          type="button"
          style={{ ...buttonBase, ...(operation === undefined ? {} : buttonDisabled) }}
          data-fde-action="refresh-sessions"
          disabled={operation !== undefined}
          onClick={() => { void loadSessions() }}
        >
          {t('session.reload')}
        </button>
        <button
          type="button"
          style={{ ...buttonPrimary, ...(operation === undefined ? {} : buttonDisabled) }}
          data-fde-action="new-session"
          disabled={operation !== undefined || sessions === undefined}
          onClick={() => { void createSession() }}
        >
          {operation === 'create' ? t('session.creating') : t('session.create')}
        </button>
      </div>

      {listError === undefined ? null : (
        <div style={{ margin: '8px 0' }}>
          <ErrorState
            message={t('session.businessListFailed', { message: listError })}
            onRetry={() => { void loadSessions() }}
            retryLabel={t('common.retry')}
          />
        </div>
      )}
      {actionError === undefined ? null : (
        <div style={{ margin: '8px 0' }} data-fde-session-create-error={actionError.kind === 'create' ? '' : undefined}>
          <ErrorState
            message={actionError.message}
            onRetry={actionError.kind === 'create' ? () => { void createSession() } : undefined}
            retryLabel={t('session.retry')}
          />
        </div>
      )}

      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', paddingTop: '8px' }}>
        {current === undefined ? (
          listError === undefined ? (
            <div role="status">{sessions === undefined ? t('session.loading') : t('session.emptyHint')}</div>
          ) : null
        ) : (
          <EmbeddedNodeSessionView
            key={`${workspacePath}:${current.dshSessionId}`}
            t={t}
            host={sessionHost}
            workspacePath={workspacePath}
            dshSessionId={current.dshSessionId}
            renderSlot={renderSlot}
          />
        )}
      </div>
    </div>
  )
}
