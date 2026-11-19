/**
 * 登录页（PRD REQ-H1、AC-H1.1~H1.4）。
 *
 * - 用户名 + 密码（密码可为空字符串：演示账号 demo / demo2 就是空密码）。
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
import { buttonBase, buttonDisabled, buttonPrimary, card, color, formLabel, formRow, inputBase, muted } from '../ui/styles.ts'

/** 登录表单状态。 */
type Phase =
  | { readonly kind: 'idle' }
  | { readonly kind: 'submitting' }
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
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })

  /** 提交登录：成功后写 token + 进入项目中心。 */
  const submit = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault()
    if (username.trim() === '') {
      setPhase({ kind: 'failed', message: t('login.usernameRequired'), unreachable: false })
      return
    }
    setPhase({ kind: 'submitting' })
    try {
      const result = await api.auth.login(username.trim(), password)
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
        <h2 style={{ margin: '0 0 6px', fontSize: '18px' }}>{t('login.title')}</h2>
        <p style={{ ...muted, margin: '0 0 16px', lineHeight: 1.6 }}>{t('login.subtitle')}</p>

        <div style={formRow}>
          <label style={formLabel} htmlFor="fde-login-username">{t('login.username')}</label>
          <input
            id="fde-login-username"
            data-fde-field="username"
            autoFocus
            autoComplete="username"
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
            autoComplete="current-password"
            value={password}
            onChange={(event) => { setPassword(event.target.value) }}
            style={inputBase}
          />
        </div>

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
            data-fde-login-submit=""
            disabled={submitting}
            style={{ ...buttonPrimary, ...(submitting ? buttonDisabled : {}), padding: '5px 16px' }}
          >
            {submitting ? t('login.submitting') : t('login.submit')}
          </button>
          <span style={muted}>{t('login.tip')}</span>
        </div>
        <div style={{ marginTop: '12px', height: '1px', background: color.border }} />
        <p style={{ ...muted, margin: '12px 0 0' }} data-fde-login-backend="">
          {t('common.backendUnreachable', { base: api.client.baseUrl })}
        </p>
        <button
          type="button"
          style={{ ...buttonBase, marginTop: '8px' }}
          onClick={() => { window.location.reload() }}
        >
          {t('common.retry')}
        </button>
      </form>
    </div>
  )
}
