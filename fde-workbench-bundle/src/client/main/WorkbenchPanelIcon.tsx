/** 侧栏「Web 工作台」面板行的图标（纯内联 SVG，颜色继承 currentColor）。 */
import type { PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-sidebar/client'

/**
 * 侧栏面板图标。侧栏自己负责可访问标签，这里只画图形；
 * 与官方面板行一致，直接返回 svg 作为行内图标子元素（不额外包 span）。
 * @param props - 侧栏给出的尺寸与选中态。
 */
export function WorkbenchPanelIcon({ size }: PropsRuntime<'sidebar.panellist'>) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="2.6" y="4.4" width="11.4" height="15.2" rx="1.8" />
      <line x1="5.2" y1="8" x2="11.4" y2="8" />
      <line x1="5.2" y1="11.2" x2="11.4" y2="11.2" />
      <line x1="5.2" y1="14.4" x2="9.2" y2="14.4" />
      <path d="M14.2 12.4h4.6" />
      <path d="M16.6 10.2l2.2 2.2-2.2 2.2" />
      <circle cx="19.9" cy="7.4" r="2.1" />
    </svg>
  )
}
