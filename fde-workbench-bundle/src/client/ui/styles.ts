/**
 * GitHub 风格基础样式值与可复用样式对象。
 *
 * 设计约束（docs/architecture.md §4.1）：
 * - 颜色一律用 DSH 主题令牌 `--dsw-alias-*`，不写死颜色，自动跟随 DSH 明暗主题；
 *   令牌变量在样式表里给出中性回退值，令牌缺失时也不会变成不可见的白字。
 * - GitHub 风格只体现在**版式与密度**：6px 圆角、1px 分隔线、紧凑行高（行高约 28–32px）、
 *   顶部下划线 Tab（选中项加粗 + 下边框）。
 * - 不引入第三方 UI 库，也不引入 CSS Modules（本包没有样式管线），一律内联样式对象。
 */
import type { CSSProperties } from 'react'

/** 语义色令牌（全部来自 DSH 主题）。 */
export const color = {
  /** 页面背景 */
  bg: 'var(--dsw-alias-bg-base, #ffffff)',
  /** 略浅的分区背景（表头、空状态） */
  bgSubtle: 'var(--dsw-alias-bg-subtle, rgba(127, 127, 127, 0.06))',
  /** 正文 */
  text: 'var(--dsw-alias-text-primary, #1f2328)',
  /** 次要文字（说明、时间） */
  textMuted: 'var(--dsw-alias-text-secondary, rgba(31, 35, 40, 0.6))',
  /** 1px 分隔线 */
  border: 'var(--dsw-alias-border-subtle, rgba(31, 35, 40, 0.15))',
  /** 强调色（链接、选中状态、进行中） */
  accent: 'var(--dsw-alias-accent, #0969da)',
  /** 危险色（删除、错误） */
  danger: 'var(--dsw-alias-danger, #cf222e)',
  /** 成功色（已完成） */
  success: 'var(--dsw-alias-success, #1a7f37)',
  /** 提示背景（错误/提示块） */
  dangerBg: 'var(--dsw-alias-danger-bg, rgba(207, 34, 46, 0.08))',
} as const

/** 圆角：GitHub 风格 6px。 */
export const RADIUS = '6px'

/** 等宽字体栈（路径、稳定键、版本号）。 */
export const MONO_FONT = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'

/** 内容区最大宽度（architecture.md §4.1）。 */
export const CONTENT_MAX_WIDTH = '1280px'

/** 按钮基础样式（次要按钮：白底 + 1px 边框）。 */
export const buttonBase: CSSProperties = {
  appearance: 'none',
  border: `1px solid ${color.border}`,
  background: color.bgSubtle,
  color: color.text,
  borderRadius: RADIUS,
  padding: '3px 12px',
  font: 'inherit',
  fontSize: '12px',
  lineHeight: '20px',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
}

/** 主按钮（强调色）。 */
export const buttonPrimary: CSSProperties = {
  ...buttonBase,
  background: color.accent,
  borderColor: color.accent,
  color: '#ffffff',
}

/** 危险按钮。 */
export const buttonDanger: CSSProperties = {
  ...buttonBase,
  color: color.danger,
}

/** 纯文本按钮（链接样式，用于「刷新」「重试」等轻量操作）。 */
export const buttonLink: CSSProperties = {
  appearance: 'none',
  border: 'none',
  background: 'transparent',
  color: color.accent,
  font: 'inherit',
  fontSize: '12px',
  padding: 0,
  cursor: 'pointer',
}

/** 禁用按钮：只降低不透明度并改光标，避免额外配色。 */
export const buttonDisabled: CSSProperties = {
  opacity: 0.5,
  cursor: 'not-allowed',
}

/** 文本输入 / 下拉框。 */
export const inputBase: CSSProperties = {
  appearance: 'none',
  border: `1px solid ${color.border}`,
  background: color.bg,
  color: color.text,
  borderRadius: RADIUS,
  padding: '3px 8px',
  font: 'inherit',
  fontSize: '13px',
  lineHeight: '20px',
  minWidth: 0,
}

/** 表单行：标签 + 控件。 */
export const formRow: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '4px',
  marginBottom: '10px',
}

/** 表单标签。 */
export const formLabel: CSSProperties = {
  fontSize: '12px',
  fontWeight: 600,
  color: color.text,
}

/** 卡片 / 区块容器（1px 边框 + 6px 圆角）。 */
export const card: CSSProperties = {
  border: `1px solid ${color.border}`,
  borderRadius: RADIUS,
  background: color.bg,
  overflow: 'hidden',
}

/** 区块标题栏（卡片的头部行）。 */
export const cardHeader: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: '8px',
  padding: '8px 12px',
  background: color.bgSubtle,
  borderBottom: `1px solid ${color.border}`,
  fontSize: '13px',
  fontWeight: 600,
  minHeight: '30px',
  boxSizing: 'border-box',
}

/** 卡片正文行：紧凑行高（约 30px）。 */
export const cardRow: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  padding: '6px 12px',
  minHeight: '30px',
  boxSizing: 'border-box',
  borderBottom: `1px solid ${color.border}`,
  fontSize: '13px',
}

/** 次要文字（说明、时间、计数）。 */
export const muted: CSSProperties = {
  color: color.textMuted,
  fontSize: '12px',
}

/** 等宽小字。 */
export const mono: CSSProperties = {
  fontFamily: MONO_FONT,
  fontSize: '12px',
}

/** 页面内水平内边距。 */
export const pagePadding = '16px 24px'

/** 页头：返回 + 标题 + 操作。 */
export const pageHeader: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '12px',
  padding: '10px 24px',
  borderBottom: `1px solid ${color.border}`,
  flex: '0 0 auto',
  minHeight: '44px',
  boxSizing: 'border-box',
}

/** 内容区（可滚动）。 */
export const contentArea: CSSProperties = {
  flex: '1 1 auto',
  minHeight: 0,
  overflow: 'auto',
  padding: pagePadding,
}
