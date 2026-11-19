/**
 * 工作台通用 UI 组件（GitHub 风格，仅用 React + 内联样式，不引入组件库）。
 *
 * 包含：页签、状态徽章、空状态、加载态、错误态（可重试）、确认 / 表单对话框、字段行、Toast 容器。
 * 所有可见文案由调用方传入（文案唯一来源是 `locales.ts`）。
 */
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import {
  RADIUS, buttonBase, buttonDisabled, buttonDanger, buttonLink, buttonPrimary, card, cardRow,
  color, formLabel, formRow, inputBase, muted,
} from './styles.ts'

/** 通用小工具：把若干样式对象合并（后者覆盖前者）。 */
export function cx(...parts: (CSSProperties | false | null | undefined)[]): CSSProperties {
  return Object.assign({}, ...parts.filter(Boolean)) as CSSProperties
}

/** 状态色调。 */
export type Tone = 'neutral' | 'info' | 'success' | 'danger'

/** 色调 → 文字色。 */
function toneColor(tone: Tone): string {
  if (tone === 'info') return color.accent
  if (tone === 'success') return color.success
  if (tone === 'danger') return color.danger
  return color.textMuted
}

/**
 * 状态徽章：带色点 + 文字（节点状态、需求状态、输出项状态共用）。
 * @param props - 文案、色调、可选的 data 属性名（便于自动化断言）。
 */
export function StatusBadge({ label, tone = 'neutral', testId }: {
  readonly label: string
  readonly tone?: Tone
  readonly testId?: string
}) {
  return (
    <span
      data-fde-badge={testId}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: '5px', fontSize: '12px',
        color: toneColor(tone), fontWeight: 500, whiteSpace: 'nowrap',
      }}
    >
      <span
        aria-hidden="true"
        style={{ width: '8px', height: '8px', borderRadius: '50%', background: toneColor(tone), flex: '0 0 auto' }}
      />
      {label}
    </span>
  )
}

/** 一页页签。 */
export interface TabDef<K extends string> {
  readonly key: K
  readonly label: string
}

/**
 * 顶部下划线 Tab（GitHub Code / Issues / Pull requests 同款）。
 * 选中项加粗并显示下边框；切换只在本组件内发生，不改变页面路由（不跳转）。
 * @param props - 页签定义、当前选中项、切换回调。
 */
export function UnderlineTabs<K extends string>({ tabs, active, onChange, testId }: {
  readonly tabs: readonly TabDef<K>[]
  readonly active: K
  readonly onChange: (key: K) => void
  readonly testId?: string
}) {
  return (
    <div
      role="tablist"
      data-fde-tabs={testId}
      style={{
        display: 'flex', alignItems: 'stretch', gap: '4px', padding: '0 24px',
        borderBottom: `1px solid ${color.border}`, flex: '0 0 auto',
      }}
    >
      {tabs.map(tab => {
        const selected = tab.key === active
        return (
          <button
            key={tab.key}
            type="button"
            role="tab"
            data-fde-tab={tab.key}
            aria-selected={selected}
            onClick={() => { if (!selected) onChange(tab.key) }}
            style={{
              appearance: 'none', border: 'none', background: 'transparent', cursor: 'pointer',
              color: selected ? color.text : color.textMuted,
              font: 'inherit', fontSize: '13px', fontWeight: selected ? 600 : 400,
              padding: '8px 8px 7px', marginBottom: '-1px',
              borderBottom: `2px solid ${selected ? color.text : 'transparent'}`,
            }}
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}

/**
 * 空状态区块。
 * @param props - 标题、说明与可选的操作区。
 */
export function EmptyState({ title, hint, action }: {
  readonly title: string
  readonly hint?: string
  readonly action?: ReactNode
}) {
  return (
    <div
      data-fde-empty=""
      style={{
        border: `1px dashed ${color.border}`, borderRadius: RADIUS, padding: '28px 16px',
        textAlign: 'center', color: color.textMuted, fontSize: '13px', lineHeight: 1.7,
        display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '8px',
      }}
    >
      <div style={{ color: color.text, fontSize: '14px', fontWeight: 600 }}>{title}</div>
      {hint === undefined ? null : <div>{hint}</div>}
      {action}
    </div>
  )
}

/**
 * 加载态。
 * @param props - 文案。
 */
export function Loading({ label }: { readonly label: string }) {
  return <div data-fde-loading="" style={{ padding: '24px 0', color: color.textMuted, fontSize: '13px' }}>{label}</div>
}

/**
 * 错误态（可重试）。后端未启动、网络失败、鉴权失败都走这里，绝不白屏。
 * @param props - 错误文案、重试回调、可选的补充说明。
 */
export function ErrorState({ message, onRetry, retryLabel, hint }: {
  readonly message: string
  readonly onRetry?: (() => void) | undefined
  readonly retryLabel?: string | undefined
  readonly hint?: string | undefined
}) {
  return (
    <div
      data-fde-error=""
      role="alert"
      style={{
        border: `1px solid ${color.danger}`, borderRadius: RADIUS, background: color.dangerBg,
        padding: '12px 14px', fontSize: '13px', lineHeight: 1.7, display: 'flex',
        flexDirection: 'column', gap: '8px', alignItems: 'flex-start',
      }}
    >
      <div data-fde-error-message="">{message}</div>
      {hint === undefined ? null : <div style={muted}>{hint}</div>}
      {onRetry === undefined
        ? null
        : <button type="button" onClick={onRetry} style={buttonBase}>{retryLabel ?? '重试'}</button>}
    </div>
  )
}

/** 模态对话框外壳（自带半透明遮罩，点击遮罩不关闭以避免误触丢输入）。 */
export function Modal({ title, children, onClose, testId }: {
  readonly title: string
  readonly children: ReactNode
  readonly onClose: () => void
  readonly testId?: string
}) {
  useEffect(() => {
    /** Esc 关闭。 */
    const onKey = (event: KeyboardEvent): void => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => { window.removeEventListener('keydown', onKey) }
  }, [onClose])
  return (
    <div
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex',
        alignItems: 'flex-start', justifyContent: 'center', paddingTop: '12vh', zIndex: 1000,
      }}
    >
      <div
        data-fde-dialog={testId}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        style={{ ...card, width: '520px', maxWidth: '92vw', display: 'flex', flexDirection: 'column' }}
      >
        <div style={{ padding: '10px 14px', borderBottom: `1px solid ${color.border}`, fontWeight: 600, fontSize: '14px' }}>
          {title}
        </div>
        {children}
      </div>
    </div>
  )
}

/**
 * 确认对话框（删除、交付等破坏性操作前的二次确认）。
 * @param props - 标题、正文、确认按钮文案与回调。
 */
export function ConfirmDialog({ title, body, confirmLabel, onConfirm, onCancel, danger, busy }: {
  readonly title: string
  readonly body: ReactNode
  readonly confirmLabel: string
  readonly onConfirm: () => void
  readonly onCancel: () => void
  readonly danger?: boolean
  readonly busy?: boolean
}) {
  return (
    <Modal title={title} onClose={onCancel} testId="confirm">
      <div style={{ padding: '14px', fontSize: '13px', lineHeight: 1.7 }}>{body}</div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', padding: '10px 14px', borderTop: `1px solid ${color.border}` }}>
        <button type="button" onClick={onCancel} style={buttonBase}>取消</button>
        <button
          type="button"
          data-fde-confirm=""
          disabled={busy === true}
          onClick={onConfirm}
          style={cx(danger === true ? buttonDanger : buttonPrimary, busy === true && buttonDisabled)}
        >
          {busy === true ? '处理中…' : confirmLabel}
        </button>
      </div>
    </Modal>
  )
}

/**
 * 文本输入对话框（创建项目、创建需求、创建 / 重命名流程共用）。
 * @param props - 标题、字段定义、提交回调；字段值由本组件自己管理。
 */
export interface TextField {
  readonly key: string
  readonly label: string
  readonly placeholder?: string | undefined
  readonly multiline?: boolean | undefined
  readonly required?: boolean | undefined
  readonly defaultValue?: string | undefined
}

/** 表单对话框。 */
export function FormDialog({ title, fields, submitLabel, onSubmit, onCancel, error, busy, extra, canSubmit: canSubmitOverride }: {
  readonly title: string
  readonly fields: readonly TextField[]
  readonly submitLabel: string
  readonly onSubmit: (values: Record<string, string>) => void
  readonly onCancel: () => void
  readonly error?: string | undefined
  readonly busy?: boolean | undefined
  /** 额外字段（例如「流程」下拉框）：由调用方渲染，`FormDialog` 不关心其内部状态 */
  readonly extra?: ReactNode
  /** 补充的可提交条件（与必填校验取「且」） */
  readonly canSubmit?: boolean
}) {
  const [values, setValues] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {}
    for (const field of fields) init[field.key] = field.defaultValue ?? ''
    return init
  })
  /** 必填项都填了才允许提交（空值提示交给后端文案兜底）。 */
  const filled = fields.every(field => field.required !== true || (values[field.key] ?? '').trim() !== '')
  const canSubmit = filled && canSubmitOverride !== false
  return (
    <Modal title={title} onClose={onCancel} testId="form">
      <div style={{ padding: '14px' }}>
        {fields.map((field, index) => (
          <div key={field.key} style={formRow}>
            <label style={formLabel} htmlFor={`fde-field-${field.key}`}>
              {field.label}
              {field.required === true ? <span style={{ color: color.danger }}> *</span> : null}
            </label>
            {field.multiline === true
              ? (
                <textarea
                  id={`fde-field-${field.key}`}
                  data-fde-field={field.key}
                  autoFocus={index === 0}
                  rows={3}
                  placeholder={field.placeholder}
                  value={values[field.key] ?? ''}
                  onChange={(event) => { setValues(prev => ({ ...prev, [field.key]: event.target.value })) }}
                  style={cx(inputBase, { resize: 'vertical' })}
                />
              )
              : (
                <input
                  id={`fde-field-${field.key}`}
                  data-fde-field={field.key}
                  autoFocus={index === 0}
                  placeholder={field.placeholder}
                  value={values[field.key] ?? ''}
                  onChange={(event) => { setValues(prev => ({ ...prev, [field.key]: event.target.value })) }}
                  style={inputBase}
                />
              )}
          </div>
        ))}
        {extra}
        {error === undefined ? null : <div data-fde-dialog-error="" style={{ color: color.danger, fontSize: '12px', marginTop: '8px' }}>{error}</div>}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', padding: '10px 14px', borderTop: `1px solid ${color.border}` }}>
        <button type="button" onClick={onCancel} style={buttonBase}>取消</button>
        <button
          type="button"
          data-fde-submit=""
          disabled={!canSubmit || busy === true}
          onClick={() => { onSubmit(values) }}
          style={cx(buttonPrimary, (!canSubmit || busy === true) && buttonDisabled)}
        >
          {busy === true ? '提交中…' : submitLabel}
        </button>
      </div>
    </Modal>
  )
}

/**
 * 一行「字段名 + 值」的只读展示行（GitHub 信息表风格）。
 * @param props - 字段名与值节点。
 */
export function FieldRow({ label, children, testId }: {
  readonly label: string
  readonly children: ReactNode
  readonly testId?: string
}) {
  return (
    <div style={{ ...cardRow, alignItems: 'flex-start' }} data-fde-field-row={testId}>
      <span style={{ width: '96px', flex: '0 0 auto', color: color.textMuted, fontSize: '12px', lineHeight: '20px' }}>{label}</span>
      <span style={{ flex: '1 1 auto', minWidth: 0, lineHeight: '20px' }}>{children}</span>
    </div>
  )
}

/** 轻提示的一条记录。 */
export interface ToastItem {
  readonly id: number
  readonly text: string
  readonly tone: Tone
}

/**
 * 轻提示容器（固定右下角，3 秒自动消失）。
 * @param props - 当前提示列表与移除回调。
 */
export function ToastHost({ items, onDismiss }: {
  readonly items: readonly ToastItem[]
  readonly onDismiss: (id: number) => void
}) {
  return (
    <div
      aria-live="polite"
      style={{ position: 'fixed', right: '16px', bottom: '16px', display: 'flex', flexDirection: 'column', gap: '8px', zIndex: 1100 }}
    >
      {items.map(item => (
        <div
          key={item.id}
          data-fde-toast=""
          role="status"
          onClick={() => { onDismiss(item.id) }}
          style={{
            ...card,
            padding: '8px 12px',
            fontSize: '13px',
            maxWidth: '420px',
            borderColor: item.tone === 'danger' ? color.danger : color.border,
            color: item.tone === 'danger' ? color.danger : color.text,
            boxShadow: '0 4px 12px rgba(0,0,0,0.12)',
            cursor: 'pointer',
          }}
        >
          {item.text}
        </div>
      ))}
    </div>
  )
}

/**
 * 轻提示状态钩子：`push` 追加一条，3 秒后自动消失。
 * @returns 当前列表、追加函数与移除函数。
 */
export function useToasts(): {
  toasts: ToastItem[]
  push: (text: string, tone?: Tone) => void
  dismiss: (id: number) => void
} {
  const nextId = useRef(1)
  const [toasts, setToasts] = useState<ToastItem[]>([])
  /** 追加一条并安排自动消失。 */
  const push = (text: string, tone: Tone = 'neutral'): void => {
    const id = nextId.current++
    setToasts(prev => [...prev, { id, text, tone }])
    window.setTimeout(() => { setToasts(prev => prev.filter(item => item.id !== id)) }, 3000)
  }
  /** 立即移除一条。 */
  const dismiss = (id: number): void => { setToasts(prev => prev.filter(item => item.id !== id)) }
  return { toasts, push, dismiss }
}

/** 小号链接按钮（列表行内的「进入」「编辑」）。 */
export const linkButtonStyle = buttonLink
