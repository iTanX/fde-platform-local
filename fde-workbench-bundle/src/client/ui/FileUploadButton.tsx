/**
 * 上传控件（节点输入用）。
 *
 * 为什么不直接用 `<input type="file">` 的可见默认样式：工作台是 GitHub 风格的紧凑列表行，
 * 原生控件会把行高撑高、且无法与右侧「上传」按钮对齐。这里把它藏起来，用按钮触发 `click()`。
 * 选择文件后立即上传（单次选择），失败时由调用方展示错误。
 */
import { useRef, useState, type ChangeEvent } from 'react'
import { buttonBase, buttonDisabled } from '../ui/styles.ts'

/**
 * 文件上传按钮。
 * @param props - 上传回调、按钮文案、是否禁用与不可见时的标识。
 */
export function FileUploadButton({ onPick, label, busyLabel, busy, disabled, testId }: {
  /** 选好文件后调用（调用方负责真正上传） */
  readonly onPick: (file: File) => void
  readonly label: string
  readonly busyLabel: string
  readonly busy: boolean
  readonly disabled: boolean
  /** 自动化断言用的 data 属性值 */
  readonly testId?: string
}) {
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [hover, setHover] = useState(false)

  /** 选中文件后回调，并清空 input.value 让同一个文件可以再次选择。 */
  const changed = (event: ChangeEvent<HTMLInputElement>): void => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file !== undefined) onPick(file)
  }

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        data-fde-file-input={testId}
        onChange={changed}
        style={{ display: 'none' }}
      />
      <button
        type="button"
        data-fde-upload={testId}
        disabled={disabled || busy}
        onClick={() => { inputRef.current?.click() }}
        onMouseEnter={() => { setHover(true) }}
        onMouseLeave={() => { setHover(false) }}
        style={{
          ...buttonBase,
          ...(disabled || busy ? buttonDisabled : {}),
          ...(hover && !disabled && !busy ? { borderColor: 'var(--dsw-alias-accent, #0969da)' } : {}),
        }}
      >
        {busy ? busyLabel : label}
      </button>
    </>
  )
}
