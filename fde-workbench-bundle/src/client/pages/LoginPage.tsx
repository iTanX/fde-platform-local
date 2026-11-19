/**
 * 登录页（PRD REQ-H1、AC-H1.1~H1.4）。
 *
 * - 支持自助注册与登录，工作台账号密码可留空。
 * - 登录成功后把 JWT 交给 `ApiClient`（只存在内存里），并把用户写进页面栈 store，进入项目中心。
 * - 失败时区分三种情况：后端不可达（可重试，不白屏）、用户名不存在（后端文案"用户不存在，请先注册"）、
 *   密码错误；文案一律直接展示后端 message，避免前后端两份口径。
 */
import { useState } from 'react'
import { isUnreachable, messageOf } from '../api/client.ts'
import type { WorkbenchApi } from '../api/index.ts'
import type { AppStore } from '../store/app-store.ts'
import type { WorkbenchTranslate } from '../session/NodeSessionView.tsx'
import { ErrorState } from '../ui/components.tsx'
import { buttonBase, buttonDisabled, buttonPrimary, card, formLabel, formRow, inputBase, muted } from '../ui/styles.ts'

/** 登录表单状态。 */
type Phase =
  | { readonly kind: 'idle' }
  | { readonly kind: 'submitting' }
  | { readonly kind: 'registered' }
  | { readonly kind: 'failed'; readonly message: string; readonly unreachable: boolean }

/**
 * 登录页。
 * @param props - 接口集合、页面栈 store 与文案函数。
 */
export function LoginPage({ api, store, t }: {
  readonly api: WorkbenchApi
  readonly store: AppStore
  readonly t: WorkbenchTranslate
}) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [registering, setRegistering] = useState(false)
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })

  const switchMode = () => {
    setRegistering(value => !value)
    setPassword('')
    setPhase({ kind: 'idle' })
  }

  /** 注册成功后回到登录表单，JWT 由登录接口签发。 */
  const submit = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault()
    if (phase.kind === 'submitting') return
    const name = username.trim()
    if (name === '') {
      setPhase({ kind: 'failed', message: t('login.usernameRequired'), unreachable: false })
      return
    }
    if (registering && !/^[A-Za-z0-9_]{3,32}$/.test(name)) {
      setPhase({ kind: 'failed', message: t('login.registerUsername'), unreachable: false })
      return
    }
    setPhase({ kind: 'submitting' })
    try {
      if (registering) {
        await api.auth.register(name, password, displayName.trim() || undefined)
        setUsername(name)
        setPassword('')
        setDisplayName('')
        setRegistering(false)
        setPhase({ kind: 'registered' })
        return
      }
      const result = await api.auth.login(name, password)
      api.client.setToken(result.token)
      store.signIn(result.user)
    } catch (error) {
      setPhase({ kind: 'failed', message: messageOf(error), unreachable: isUnreachable(error) })
    }
  }

  const failed = phase.kind === 'failed'
  const submitting = phase.kind === 'submitting'

  return (
    <div
      data-fde-page="login"
      style={{ display: 'flex', justifyContent: 'center', alignItems: 'flex-start', padding: '8vh 16px 24px', overflow: 'auto' }}
    >
      <form
        onSubmit={(event) => { void submit(event) }}
        style={{ ...card, width: '400px', maxWidth: '100%', padding: '20px' }}
      >
        <h2 style={{ margin: '0 0 6px', fontSize: '18px' }}>{t(registering ? 'login.registerTitle' : 'login.title')}</h2>
        <p style={{ ...muted, margin: '0 0 16px', lineHeight: 1.6 }}>{t(registering ? 'login.registerUsername' : 'login.subtitle')}</p>

        <div style={formRow}>
          <label style={formLabel} htmlFor="fde-login-username">{t('login.username')}</label>
          <input
            id="fde-login-username"
            data-fde-field="username"
            autoFocus
            autoComplete="username"
            disabled={submitting}
            maxLength={registering ? 32 : undefined}
            value={username}
            onChange={(event) => { setUsername(event.target.value) }}
            style={inputBase}
          />
        </div>
        <div style={formRow}>
          <label style={formLabel} htmlFor="fde-login-password">{t('login.password')}</label>
          <input
            id="fde-login-password"
            data-fde-field="password"
            type="password"
            autoComplete={registering ? 'new-password' : 'current-password'}
            disabled={submitting}
            maxLength={registering ? 72 : undefined}
            value={password}
            onChange={(event) => { setPassword(event.target.value) }}
            style={inputBase}
          />
        </div>

        {registering ? (
          <div style={formRow}>
            <label style={formLabel} htmlFor="fde-register-display-name">{t('login.displayName')}</label>
            <input id="fde-register-display-name" data-fde-field="displayName" autoComplete="nickname" disabled={submitting}
              maxLength={64} value={displayName} onChange={event => setDisplayName(event.target.value)} style={inputBase} />
          </div>
        ) : null}
        {phase.kind === 'registered' ? <p role="status" style={muted}>{t('login.registered')}</p> : null}
        {failed
          ? (
            <div style={{ marginBottom: '12px' }}>
              <ErrorState
                message={phase.message}
                {...(phase.unreachable
                  ? { hint: t('common.backendUnreachable', { base: api.client.baseUrl }) }
                  : {})}
              />
            </div>
          )
          : null}

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <button
            type="submit"
            data-fde-login-submit={registering ? undefined : ''}
            data-fde-register-submit={registering ? '' : undefined}
            disabled={submitting}
            style={{ ...buttonPrimary, ...(submitting ? buttonDisabled : {}), padding: '5px 16px' }}
          >
            {t(registering
              ? (submitting ? 'login.registering' : 'login.register')
              : (submitting ? 'login.submitting' : 'login.submit'))}
          </button>
          <button type="button" data-fde-auth-mode="" disabled={submitting} style={buttonBase} onClick={switchMode}>
            {t(registering ? 'login.backToLogin' : 'login.register')}
          </button>
        </div>
        {/*
          后端连通状态只在"确实连不上"时出现（phase.unreachable）。
          这里曾经无条件渲染过一句"无法连接工作台后端"+重试按钮，
          导致登录页底部永远挂着一个错误提示，而后端其实正常——误导读排查方向。
          失败提示统一由上面的 ErrorState 呈现，这里不再重复。
        */}
      </form>
    </div>
  )
}
