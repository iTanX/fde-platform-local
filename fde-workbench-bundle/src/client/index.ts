/**
 * Web 工作台插件 —— Client 半（浏览器内，与 DSH 前端同一个 React 树）。
 *
 * 本任务（T5-3）在 T0-3 骨架上补齐业务客户端，改动集中在「注入什么」与「面板渲染什么」：
 * 1. 侧栏全局面板列表注册「Web 工作台」入口（`sidebar.panellist`，order -100 = 列表第一行）；
 * 2. 为同一个面板 id 注册 `main` keyed 面板，渲染工作台外壳 `WorkbenchMain`
 *    （登录 → 项目中心 → 项目四 Tab → 需求 → 节点页的完整页面栈）；
 * 3. 注册会话作用域单槽位 `workbench.node.chat`（两级槽位：外壳 + 内容座席），
 *    节点页「会话」视图用的就是这个槽位 —— 槽位结构与 T0-3 实测通过的版本**完全一致**，
 *    只是嵌套层级换到工作台外壳之内。
 *
 * 为什么槽位结构不动：T0-4 已经实测过「注册项声明非 root 子槽位 → 框架发放 SessionProvider 座位」
 * 这条链路（README §3 的四个坑全部踩过）；这里只把参数（接口集合、页面栈 store、新建会话能力）
 * 注入给 `main` 面板，由面板内部的页面决定何时渲染这个槽位。
 */
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/dsh-client-locale/client'
import type { MainPanelId } from '@deepseek-ai/dsh-client-ui-layout/client'
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import type {} from '@deepseek-ai/dsh-client-ui-renderer/client'
import type {} from '@deepseek-ai/dsh-client-ui-sidebar/client'
import type { SessionReference } from '@deepseek-ai/dsh-api-session-controller/client'
import type {} from '@deepseek-ai/dsh-api-workspace-controller/client'
import type { SessionId } from '@deepseek-ai/dsh-session/types'
import { createWorkbenchApi, type WorkbenchApi } from './api/index.ts'
import { ApiClient } from './api/client.ts'
import { dicts, NS, type WorkbenchLocaleKey } from './locales.ts'
import { WorkbenchPanelIcon } from './main/WorkbenchPanelIcon.tsx'
import { WorkbenchMain, type WorkbenchMainInjected } from './main/WorkbenchMain.tsx'
import { AppStore } from './store/app-store.ts'
import { WorkbenchChatShell, WorkbenchConversationPanel, type NodeChatOwnerProps } from './session/NodeSessionView.tsx'

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface LocaleNamespaceMap {
    /** 工作台面板自身的文案（页面、表单、会话视图、错误态）。 */
    workbench: WorkbenchLocaleKey
  }

  interface SlotMap {
    /**
     * 工作台面板内嵌入的会话**外壳**（会话作用域单槽位）。
     * 组件 `WorkbenchChatShell`：`<SessionProvider session={reference}>` 绑定会话后，
     * 渲染自己的会话子槽位 `workbench.node.chat.conversation`。
     * 由外层 `main` 面板通过 `renderSlot('workbench.node.chat', { reference })` 渲染。
     *
     * `owner` 声明渲染点传来的参数：槽位注册项本身不接收 owner 参数（`register()` 只传框架座席），
     * owner 参数在**渲染时**与座席合并后落到组件上，所以必须由 SlotMap 的 `owner` 类型表声明，
     * 组件（以及外层 renderSlot 调用处）才拿得到 `reference`。
     */
    'workbench.node.chat': { kind: 'single'; scope: 'session'; owner: NodeChatOwnerProps }
    /**
     * 会话内容座席：外层槽位 `workbench.node.chat` 的会话作用域子槽位。
     * 官方对应项：ui-subagent 的 `sidebar.chat.conversation`（由 `ConversationSlotPanel` 承接）。
     *
     * 这一层存在的意义：渲染框架只在**注册项自身声明了非 root 子槽位**时才给它发放
     * `SessionProvider` 座位（ui-renderer `scoped-slots.tsx` 的 `standardKit()`），
     * 外层槽位因此必须声明本子槽位，才能在自己的组件里 `<SessionProvider>` 包住它。
     */
    'workbench.node.chat.conversation': { kind: 'single'; scope: 'session' }
  }
}

/** 侧栏入口与 `main` 面板共用同一个面板 id（官方 plugin-manager / ui-schedule 同款做法）。 */
export const PANEL_ID = 'workbench' as MainPanelId

export const inject = ['slots', 'locale', 'layout', 'sessions', 'workspaces']

/** DSH 注册工作区时会规范化绝对路径，并复用同一个真实目录的注册项。 */
async function requirementWorkspace(ctx: ClientContext, path: string) {
  return ctx.workspaces.list.getSnapshot().items.find(item => item.path === path)
    ?? ctx.workspaces.create({ path })
}

/**
 * 注册工作台入口、面板与嵌入会话槽位。
 * @param ctx - 浏览器侧插件上下文。
 */
export function apply(ctx: ClientContext): void {
  ctx.effect(() => ctx.locale.register(NS, dicts), 'fde-workbench: dictionaries')
  const t = ctx.locale.bind(NS)

  // 业务接口与页面栈：整个插件生命周期内只有一个实例（登出只清 token 与页面栈，不重建）
  const apiClient = new ApiClient()
  const api: WorkbenchApi = createWorkbenchApi(apiClient)
  const store = new AppStore()
  const sessionHost: WorkbenchMainInjected['sessionHost'] = {
    sessions: ctx.sessions,
    async createSession(workspacePath) {
      const workspace = await requirementWorkspace(ctx, workspacePath)
      // 省略 sessionId 会创建全新会话。connectWorkspace 会复用空白会话。
      return ctx.sessions.create({ workspaceId: workspace.workspaceId })
    },
    async resolveSession(workspacePath, dshSessionId) {
      const workspace = await requirementWorkspace(ctx, workspacePath)
      const id = dshSessionId as SessionId
      let summary = ctx.sessions.list.getSnapshot().byId[id]
      if (summary === undefined || summary.cwd !== workspace.path) {
        await ctx.sessions.refresh()
        summary = ctx.sessions.list.getSnapshot().byId[id]
      }
      if (summary === undefined) throw new Error(t('session.missing'))
      if (summary.cwd !== workspace.path || summary.origin === 'subagent') {
        throw new Error(t('session.wrongWorkspace'))
      }
      return id
    },
  }
  // 401（token 过期）统一回到登录页：由页面栈驱动，不做整页跳转
  apiClient.onUnauthorized(() => { store.signOut() })

  // 工作台整页：`main` 是 keyed 槽位，key 必须与侧栏入口 id 相同。
  // 所有跨服务依赖集中在 inject 里显式传入，组件本身不读浏览器全局。
  // `children` 声明本面板拥有 `workbench.node.chat` 子槽位：声明即授权，
  // 只有声明它的注册项才能 `renderSlot` 它。槽位自身是 session 作用域，
  // 会话绑定由槽位组件的 <SessionProvider> 建立（官方 ui-subagent 同款）。
  ctx.effect(() => ctx.slots.inject('main', () => ctx.slots.register({
    name: 'main',
    key: PANEL_ID,
    locale: NS,
    children: { 'workbench.node.chat': { kind: 'single', scope: 'session' } },
    inject: (): WorkbenchMainInjected => ({
      layout: ctx.layout,
      api,
      store,
      sessionHost,
    }),
  }, WorkbenchMain)), 'fde-workbench: main panel')

  // 嵌入会话的两个槽位都**依赖 `main` 面板的 children 声明**才能注册：
  // `register()` 遇到未声明的槽位会直接抛错
  // （`slot "…" is not declared (a parent entry's children table must declare it)`）。
  // 因此必须用 `ctx.slots.inject(key, cb)`——它在槽位被声明的**那一刻**才同步执行 cb
  // （ui-renderer `registry.ts` 的 `inject()`：已有声明则立即执行，否则等 register 提交后再跑），
  // 而不是直接 `ctx.slots.register(...)`：插件加载顺序由 bundle 决定，不能假设 `main` 已经先注册。
  //
  // ① 会话外壳槽位：会话作用域单槽位 + 会话子槽位 → 组件 WorkbenchChatShell
  //    （官方 ui-subagent 的 `sidebar.right.pane.tab` + SidebarChatTab 的角色）。
  //    必须声明非 root 子槽位：渲染框架只在 `children` 里含非 root 槽位时才给注册项
  //    发放 `SessionProvider` 座位（ui-renderer `scoped-slots.tsx` 的 `standardKit()`），
  //    没有它就撑不起 `<SessionProvider>`，会话作用域无从建立。
  ctx.effect(() => ctx.slots.inject('workbench.node.chat', () => ctx.slots.register({
    name: 'workbench.node.chat',
    children: { 'workbench.node.chat.conversation': { kind: 'single', scope: 'session' } },
  }, WorkbenchChatShell)), 'fde-workbench: embedded conversation slot')

  // ② 会话内容座席：真正的 conversation.content 工厂调用方 → 组件 WorkbenchConversationPanel
  //    （官方 ui-subagent 的 `sidebar.chat.conversation` + ConversationSlotPanel 的角色）。
  //    它已在 <SessionProvider> 之内，直接 renderFactorySlot('conversation.content', …)。
  ctx.effect(() => ctx.slots.inject('workbench.node.chat.conversation', () => ctx.slots.register({
    name: 'workbench.node.chat.conversation',
  }, WorkbenchConversationPanel)), 'fde-workbench: embedded conversation content')

  // 侧栏全局面板列表入口：order -100 排在插件管理器（0）与定时任务（10）之前，
  // 即面板列表第一行、会话浏览区之上。
  ctx.effect(() => ctx.slots.inject('sidebar.panellist', () => ctx.slots.register({
    name: 'sidebar.panellist',
    id: PANEL_ID,
    order: -100,
    locale: NS,
    label: () => t('panel.label'),
  }, WorkbenchPanelIcon)), 'fde-workbench: sidebar entry')
}

/** 供上层复用的类型导出。 */
export type { NodeChatOwnerProps } from './session/NodeSessionView.tsx'
export type { SessionReference }
