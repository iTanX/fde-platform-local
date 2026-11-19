/**
 * 功能域上下文（把 `apply()` 里构造的服务、store 与动作注入到页面组件）。
 *
 * 页面组件都通过 `useWorkbench()` 读取这些依赖，而不通过 props 层层穿透：
 * 页面层级有 4~5 层（项目中心 → 项目 → 需求 → 节点），逐层透传会淹没真正的入参。
 * Provider 是模块内私有的，唯一入口是 `WorkbenchMain`（它拿到 `main` 槽位注入的参数后挂载）。
 */
import { createContext, useContext, type ReactNode } from 'react'
import type { WorkbenchApi } from '../api/index.ts'
import type { AppStore } from '../store/app-store.ts'
import type { ToastItem, Tone } from '../ui/components.tsx'
import type { NodeChatOwnerProps, SessionHost, WorkbenchTranslate } from '../session/NodeSessionView.tsx'
import type { SessionCreateResult } from '../ui/session-bridge.ts'

/**
 * 会话宿主能力：除了 embed 用的 sessions 引用，还要能「新建 DSH 会话并拿到 SessionId」。
 *
 * 为什么需要单独提供：`NodeSessionView` 只支持「挑一个已有会话嵌入」（T0-3 冒烟形态），
 * 而节点页要求「新建会话 → 登记到需求 → 切换到新会话」。新建走官方导航动作
 * （`ctx.uiWorkspace.connectWorkspace`，它返回 SessionId，且不会把侧栏选中态切走），
 * 之后由本插件调用业务接口把会话登记到需求节点（AC-X2.1）。
 */
export interface WorkbenchSessionHost extends SessionHost {
  /**
   * 新建一个 DSH 会话并返回其 SessionId。
   * @param requirementTitle - 需求标题（用于新建会话的标题）
   */
  readonly createSession: (requirementTitle: string) => Promise<SessionCreateResult>
}

/** 功能域上下文值。 */
export interface WorkbenchContextValue {
  /** 业务接口集合 */
  readonly api: WorkbenchApi
  /** 页面栈与当前用户 / 当前项目 */
  readonly store: AppStore
  /** 文案翻译函数（工作台命名空间） */
  readonly t: WorkbenchTranslate
  /** 会话宿主（嵌入 + 新建） */
  readonly sessionHost: WorkbenchSessionHost
  /** 渲染本插件声明的会话槽位（与 T0-3 相同，由渲染框架注入） */
  readonly renderSlot: (name: 'workbench.node.chat', owner: NodeChatOwnerProps) => ReactNode
  /** 轻提示：追加一条 */
  readonly toast: (text: string, tone?: Tone) => void
  /** 当前登录用户名（用于「提交人」等展示） */
  readonly currentUsername: string | undefined
}

/** 上下文对象（模块私有）。 */
const WorkbenchContext = createContext<WorkbenchContextValue | undefined>(undefined)

/** 渲染上下文（仅 `WorkbenchMain` 使用）。 */
export function WorkbenchProvider(props: { readonly value: WorkbenchContextValue; readonly children: ReactNode }) {
  return <WorkbenchContext.Provider value={props.value}>{props.children}</WorkbenchContext.Provider>
}

/**
 * 读取功能域上下文。
 * @returns 上下文值；在 Provider 之外调用会抛错（说明装配有误，应尽早暴露）。
 */
export function useWorkbench(): WorkbenchContextValue {
  const value = useContext(WorkbenchContext)
  if (value === undefined) throw new Error('useWorkbench() 必须在 WorkbenchProvider 内调用')
  return value
}

/** ToastItem 的具名导出（页面需要类型时从这里取，避免再引 components）。 */
export type { ToastItem }
