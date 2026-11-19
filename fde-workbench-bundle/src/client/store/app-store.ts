/**
 * 页面栈与应用状态（工作台面板内的「页面路由」）。
 *
 * 为什么不用 URL 路由：工作台是 DSH 的 `main` 面板，不是独立 SPA，没有自己的 location；
 * 页面栈因此放在插件自己的 snapshot store 里（页面栈：项目中心 → 项目四 Tab → 需求详情 → 节点页）。
 *
 * 实现方式是**极简外部 store + `useSyncExternalStore`**（约 40 行），没有引入任何状态库：
 * DSH 的 slot store 座位（`store:` 声明 + `useStore`）要求注册项类型与 apply 里创建的句柄对齐，
 * 对「页面栈」这种插件私有的 UI 状态属于过度设计；`useSyncExternalStore` 是 React 官方 API，
 * 语义与 DSH 内部使用的 uSES 完全一致（快照引用不变即不重渲染）。
 */
import { useCallback, useSyncExternalStore } from 'react'
import type { ProjectVO, RequirementVO, UserVO } from '../api/types.ts'

/** 工作台页面。 */
export type Page =
  /** 已登录：项目中心 */
  | { readonly kind: 'projects' }
  /** 项目四 Tab（需求 / 流程 / 技能 / 成员） */
  | { readonly kind: 'project'; readonly projectId: number; readonly tab: ProjectTab }
  /** 流程节点编辑器 */
  | { readonly kind: 'workflow'; readonly projectId: number; readonly workflowId: number }
  /** 需求详情（节点时间线） */
  | { readonly kind: 'requirement'; readonly projectId: number; readonly requirementId: number }
  /** 节点页（会话 / 节点信息 两个视图） */
  | {
    readonly kind: 'node'
    readonly projectId: number
    readonly requirementId: number
    readonly nodeId: number
    readonly view: 'session' | 'info'
  }

/** 项目内的四个 Tab（PRD AC-B1.1）。 */
export type ProjectTab = 'requirements' | 'workflows' | 'skills' | 'members'

/** 应用状态。 */
export interface AppState {
  /** 登录用户；undefined 表示未登录 → 显示登录页 */
  readonly user: UserVO | undefined
  /** 当前项目（进入项目后缓存，供页头与面包屑用） */
  readonly project: ProjectVO | undefined
  /** 页面栈：最后一个元素是当前页面；用数组保存以便「返回」可回溯 */
  readonly stack: readonly Page[]
}

/** 初始状态。 */
const INITIAL: AppState = { user: undefined, project: undefined, stack: [{ kind: 'projects' }] }

/**
 * 应用 store：状态 + 订阅 + 纯函数式更新。
 *
 * 更新一律产生新的快照对象（不可变更新），这样 `useSyncExternalStore` 的引用比较才成立。
 */
export class AppStore {
  private state: AppState = INITIAL
  private readonly listeners = new Set<() => void>()

  /** 读取当前快照（给 `useSyncExternalStore` 用，必须是稳定函数引用）。 */
  readonly getSnapshot = (): AppState => this.state

  /**
   * 订阅状态变化。
   * @param listener - 回调
   * @returns 取消订阅函数
   */
  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  /**
   * 更新状态（内部使用不可变合并）。
   * @param patcher - 返回新状态片段或整体新状态的函数
   */
  update(patcher: (state: AppState) => AppState): void {
    const next = patcher(this.state)
    if (next === this.state) return
    this.state = next
    for (const listener of [...this.listeners]) listener()
  }

  /**
   * 登录成功：记录用户，并把页面栈重置为项目中心。
   * @param user - 登录用户
   */
  signIn(user: UserVO): void {
    this.update(() => ({ user, project: undefined, stack: [{ kind: 'projects' }] }))
  }

  /** 登出：清空用户与页面栈（同时由调用方清掉 API token）。 */
  signOut(): void {
    this.update(() => INITIAL)
  }

  /**
   * 压入一个页面（并更新当前项目缓存）。
   * @param page - 目标页面
   * @param project - 可选：目标页面所属项目（用于页头展示与返回）
   */
  push(page: Page, project?: ProjectVO): void {
    this.update(state => ({
      ...state,
      project: project ?? state.project,
      stack: [...state.stack, page],
    }))
  }

  /**
   * 替换栈顶页面（例如项目内切换 Tab、节点页切换视图），不改变栈深。
   * @param page - 新栈顶
   * @param project - 可选：新的项目缓存
   */
  replace(page: Page, project?: ProjectVO): void {
    this.update(state => ({
      ...state,
      project: project ?? state.project,
      stack: [...state.stack.slice(0, -1), page],
    }))
  }

  /** 返回上一页；栈只剩一页时不动。 */
  pop(): void {
    this.update((state) => {
      if (state.stack.length <= 1) return state
      return { ...state, stack: state.stack.slice(0, -1) }
    })
  }

  /**
   * 回到项目中心（丢弃整个页面栈）。
   */
  goProjects(): void {
    this.update(state => ({ ...state, project: undefined, stack: [{ kind: 'projects' }] }))
  }

  /**
   * 跳转到某个需求详情：把栈裁剪成「项目中心 → 项目（需求 Tab） → 需求」。
   * 用于从节点页 / 编辑器返回，保证「返回」按钮的落点符合直觉。
   * @param requirement - 需求本体（含 projectId）
   */
  openRequirement(requirement: RequirementVO): void {
    this.push({ kind: 'requirement', projectId: requirement.projectId, requirementId: requirement.id })
  }
}

/**
 * 订阅整个应用状态。
 * @param store - 应用 store
 * @returns 当前状态（状态变化时触发重渲染）
 */
export function useAppState(store: AppStore): AppState {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot)
}

/**
 * 常用动作的语义化封装（页面组件里只调这些，避免各页面各写一套栈操作）。
 * @param store - 应用 store
 */
export function useNavigation(store: AppStore) {
  const goProjects = useCallback(() => { store.goProjects() }, [store])
  const openProject = useCallback((project: ProjectVO, tab: ProjectTab = 'requirements') => {
    store.update(state => ({
      ...state,
      project,
      // 栈里已有本项目时回到它（并保持栈深），否则压入新的项目页
      stack: state.stack.some(page => page.kind === 'project' && page.projectId === project.id)
        ? state.stack
        : [...state.stack, { kind: 'project', projectId: project.id, tab }],
    }))
  }, [store])
  const replaceTab = useCallback((tab: ProjectTab) => {
    store.update((state) => {
      const top = state.stack[state.stack.length - 1]
      if (top?.kind !== 'project') return state
      return { ...state, stack: [...state.stack.slice(0, -1), { ...top, tab }] }
    })
  }, [store])
  const openWorkflow = useCallback((projectId: number, workflowId: number) => {
    store.push({ kind: 'workflow', projectId, workflowId })
  }, [store])
  const openRequirement = useCallback((projectId: number, requirementId: number) => {
    store.push({ kind: 'requirement', projectId, requirementId })
  }, [store])
  const openNode = useCallback((projectId: number, requirementId: number, nodeId: number, view: 'session' | 'info' = 'session') => {
    store.push({ kind: 'node', projectId, requirementId, nodeId, view })
  }, [store])
  const switchNodeView = useCallback((view: 'session' | 'info') => {
    store.update((state) => {
      const top = state.stack[state.stack.length - 1]
      if (top?.kind !== 'node') return state
      return { ...state, stack: [...state.stack.slice(0, -1), { ...top, view }] }
    })
  }, [store])
  const back = useCallback(() => { store.pop() }, [store])
  return { goProjects, openProject, replaceTab, openWorkflow, openRequirement, openNode, switchNodeView, back }
}
