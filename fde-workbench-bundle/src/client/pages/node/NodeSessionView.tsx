/**
 * 「会话」视图：会话切换器 + 新建会话 + touch + 嵌入的 DSH 原生会话。
 *
 * 职责边界（重要）：
 * - 真正的会话渲染仍然交给 `session/NodeSessionView.tsx`（T0-3 已实测通过：两级槽位 + `SessionProvider`
 *   + `conversation.content`）。它的槽位结构**不能改**，所以这里采用「在上层包业务组件」的做法：
 *   顶部一条 GitHub 风格的会话栏（本需求会话列表 / 新建会话），下方嵌 `NodeSessionView`。
 * - 默认会话的解析遵循 AC-F7.2 / AC-F7.4：节点 `defaultSessionId`（节点绑定优先）→
 *   本需求登记会话中最近活跃者 → 交给 `NodeSessionView` 自己挑（README §6 的既有行为）。
 * - 会话切换时用 `key` 重新挂载 `NodeSessionView`，让它按新的会话条件重新 retain；
 *   不用改它的内部实现。
 * - 每次挂载 / 切换 / 新建都会 `touch` 一次最后活跃时间（失败不影响对话）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { messageOf } from '../../api/client.ts'
import type { RequirementVO, SessionVO } from '../../api/types.ts'
import { useWorkbench } from '../../store/context.tsx'
import { NodeSessionView as EmbeddedNodeSessionView } from '../../session/NodeSessionView.tsx'
import { ErrorState, StatusBadge } from '../../ui/components.tsx'
import { dshSessionTitles, mergeSessionChoices, shortenSessionId, type SessionChoice } from '../../ui/session-bridge.ts'
import { buttonBase, buttonDisabled, buttonPrimary, color, muted } from '../../ui/styles.ts'
import { formatTime } from './NodeInfoPanel.tsx'

/**
 * 节点页「会话」视图。
 * @param props - 需求本体（用于新建会话标题）、节点信息（只读标记 / 默认会话 / 节点 ID）。
 */
export function NodeSessionPanel({ requirement, defaultSessionId, readonly }: {
  readonly requirement: RequirementVO
  /** 节点默认会话（节点绑定优先） */
  readonly defaultSessionId: string | null
  /** 只读时不显示「新建会话」（后端也会拒绝，前端不制造无效入口） */
  readonly readonly: boolean
}) {
  const { api, t, sessionHost, renderSlot } = useWorkbench()
  const [sessions, setSessions] = useState<SessionVO[] | undefined>(undefined)
  const [listError, setListError] = useState<string | undefined>(undefined)
  const [selected, setSelected] = useState<string | undefined>(undefined)
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | undefined>(undefined)
  // 新登记的会话：登记成功后立刻切换到它（AC-X2.2：新建的会话成为默认会话）
  const preferredRef = useRef<string | undefined>(undefined)

  /** 读取本需求会话列表（业务侧登记）。失败只提示，不阻断会话渲染。 */
  const loadSessions = useCallback(async (): Promise<SessionVO[] | undefined> => {
    try {
      const list = await api.session.list(requirement.id)
      setSessions(list)
      setListError(undefined)
      return list
    } catch (error) {
      setListError(messageOf(error))
      return undefined
    }
  }, [api.session, requirement.id])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      await sessionHost.sessions.refresh().catch(() => undefined)
      const list = await loadSessions()
      if (cancelled) return
      // 默认会话：偏好（刚新建的） → 节点默认会话 → 最近活跃的业务会话
      const fallback = list?.[0]?.dshSessionId
      setSelected(current => current ?? preferredRef.current ?? defaultSessionId ?? fallback ?? undefined)
    })()
    return () => { cancelled = true }
  }, [sessionHost, loadSessions, defaultSessionId])

  /** 会话切换器选项：业务会话 + DSH 标题兜底。 */
  const choices = useMemo<SessionChoice[]>(
    () => mergeSessionChoices(sessions ?? [], dshSessionTitles(sessionHost), defaultSessionId),
    // sessionHost 的 sessions 列表是快照源：每次渲染取一次标题即可，依赖里不放它以免频繁重建
    [sessions, defaultSessionId, sessionHost],
  )

  /** 切换到某个会话，并 touch 更新时间（失败静默）。 */
  const switchTo = (choice: SessionChoice): void => {
    setSelected(choice.dshSessionId)
    if (choice.businessId !== undefined) {
      void api.session.touch(choice.businessId).then(() => loadSessions()).catch(() => undefined)
    }
  }

  /**
   * 新建会话：官方导航动作创建 DSH 会话 → 业务登记（绑定当前节点）→ 切换过去。
   */
  const createSession = async (): Promise<void> => {
    setCreating(true)
    setCreateError(undefined)
    try {
      const created = await sessionHost.createSession(requirement.title)
      if (created.dshSessionId === undefined) {
        setCreateError(t('session.createFailed', { message: created.registerError ?? t('common.unknown') }))
        return
      }
      if (created.registerError !== undefined) {
        // DSH 会话已建好但业务登记失败：仍然允许在当前页面切换过去，只是不会出现在列表里
        setCreateError(t('session.bindFailed', { message: created.registerError }))
      } else {
        preferredRef.current = String(created.dshSessionId)
        await loadSessions()
      }
      setSelected(String(created.dshSessionId))
    } catch (error) {
      setCreateError(t('session.createFailed', { message: messageOf(error) }))
    } finally {
      setCreating(false)
    }
  }

  /** 挂载即 touch 一次（记录"打开过这个会话"）。 */
  useEffect(() => {
    if (selected === undefined) return
    const found = sessions?.find(item => item.dshSessionId === selected)
    if (found === undefined) return
    void api.session.touch(found.id).catch(() => undefined)
    // 只在切换会话时 touch，避免每次列表刷新都写库
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected])

  const notRegistered = selected === undefined
    ? false
    : (sessions !== undefined && sessions.length > 0 && !sessions.some(item => item.dshSessionId === selected))

  return (
    <div data-fde-view="session" style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {/* 会话栏：本需求会话切换器 + 新建会话（AC-F7.2 / AC-X1.1） */}
      <div
        style={{
          display: 'flex', alignItems: 'center', gap: '8px', padding: '6px 0 8px',
          borderBottom: `1px solid ${color.border}`, flex: '0 0 auto', flexWrap: 'wrap',
        }}
      >
        <span style={{ ...muted, flex: '0 0 auto' }}>{t('session.requirementSessions')}</span>
        <select
          aria-label={t('session.switch')}
          data-fde-session-switch=""
          value={selected ?? ''}
          onChange={(event) => {
            const choice = choices.find(item => item.dshSessionId === event.target.value)
            if (choice !== undefined) switchTo(choice)
            else setSelected(event.target.value === '' ? undefined : event.target.value)
          }}
          disabled={choices.length === 0}
          style={{
            border: `1px solid ${color.border}`, borderRadius: '6px', background: 'transparent', color: 'inherit',
            font: 'inherit', fontSize: '12px', padding: '2px 8px', maxWidth: '380px', minWidth: '180px',
          }}
        >
          {choices.length === 0
            ? <option value="">{t('session.noSession')}</option>
            : null}
          {choices.map(choice => (
            <option key={choice.dshSessionId} value={choice.dshSessionId}>
              {choice.title}
              {choice.isNodePrimary ? ` · ${t('session.default')}` : ''}
              {choice.lastActiveAt === undefined ? '' : ` · ${t('session.lastActive', { time: formatTime(choice.lastActiveAt) })}`}
            </option>
          ))}
        </select>
        {choices.length === 0
          ? null
          : (
            <StatusBadge
              label={t('session.count', { count: choices.length })}
              tone="neutral"
              testId="session-count"
            />
          )}
        <span style={{ flex: '1 1 auto' }} />
        <button
          type="button"
          style={{ ...buttonBase, ...(readonly || creating ? buttonDisabled : {}) }}
          data-fde-action="refresh-sessions"
          disabled={creating}
          onClick={() => { void loadSessions() }}
        >
          {t('session.reload')}
        </button>
        {readonly
          ? null
          : (
            <button
              type="button"
              style={{ ...buttonPrimary, ...(creating ? buttonDisabled : {}) }}
              data-fde-action="new-session"
              disabled={creating}
              onClick={() => { void createSession() }}
            >
              {creating ? t('session.creating') : t('session.create')}
            </button>
          )}
      </div>

      {listError === undefined
        ? null
        : (
          <div style={{ margin: '8px 0' }}>
            <ErrorState
              message={t('session.businessListFailed', { message: listError })}
              onRetry={() => { void loadSessions() }}
              retryLabel={t('common.retry')}
            />
          </div>
        )}
      {createError === undefined ? null : (
        <div style={{ margin: '8px 0' }} data-fde-session-create-error="">
          <ErrorState message={createError} onRetry={() => { void createSession() }} retryLabel={t('session.retry')} />
        </div>
      )}
      {notRegistered && selected !== undefined
        ? <div style={{ ...muted, margin: '8px 0' }} data-fde-session-unregistered="">{t('session.notRegistered')} {shortenSessionId(selected)}</div>
        : null}

      {/*
        嵌入会话本体：NodeSessionView 的槽位结构保持不变，只在其外层包业务组件。
        key 带上会话标识：切换会话时重新挂载，让它按新的会话条件重新刷新与 retain。
        新建会话后 `sessions` 变化会触发 NodeSessionView 重新挑选（它挑列表里第一个非子会话），
        因此这里额外提供「刚选中的会话」——挂载前先把列表刷新一次，保证它能被挑到。
      */}
      <div style={{ flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column', paddingTop: '8px' }}>
        <EmbeddedNodeSessionView
          key={selected ?? 'auto'}
          t={t}
          host={sessionHost}
          onCreateSession={() => { void createSession() }}
          renderSlot={renderSlot}
        />
      </div>
    </div>
  )
}
