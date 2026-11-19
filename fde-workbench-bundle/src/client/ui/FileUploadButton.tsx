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

  /**
   * 选中文件后回调。
   * <p>
   * <b>顺序很重要</b>：`input.value = ''` 会把 `files` 一起清空（FileList 是实时引用），
   * 先清空再读会让文件变成空对象——上传请求带着 0 字节的 body 出去，后端直接拒收，
   * 表现就是"选完文件页面闪一下、什么都没发生"。
   * 所以先把 File 取出来，再用 `queueMicrotask` 延后清空，让同一个文件下次还能再选。
   *
   * @param event - input 的 change 事件
   */
  const changed = (event: ChangeEvent<HTMLInputElement>): void => {
    const input = event.target
    const file = input.files?.[0]
    if (file === undefined) return
    onPick(file)
    // 延后清空：立刻清空会让上面已经交给调用方的 File 引用失效
    queueMicrotask(() => { input.value = '' })
  }

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        data-fde-file-input={testId}
        onChange={changed}
        // 用"移出可视区 + 0 尺寸"而不是 display:none：
        // display:none 的 input 在部分浏览器 / 嵌入容器（如插件面板的 iframe 或 portal）里
        // 调用 click() 不会弹出文件选择框，表现为"点了上传没反应、也发不出请求"。
        // 视觉上仍然不可见，可访问性上仍可被聚焦触发。
        style={{
          position: 'absolute',
          width: '1px',
          height: '1px',
          padding: 0,
          margin: '-1px',
          overflow: 'hidden',
          clip: 'rect(0 0 0 0)',
          whiteSpace: 'nowrap',
          border: 0,
          opacity: 0,
        }}
        tabIndex={-1}
        aria-hidden="true"
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
