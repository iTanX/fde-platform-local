/**
 * Web 工作台插件 —— Client 半（浏览器内，与 DSH 前端同一个 React 树）。
 *
 * 本阶段只做三件事：
 * 1. 侧栏全局面板列表注册「Web 工作台」入口（`sidebar.panellist`，order -100 = 列表第一行）；
 * 2. 为同一个面板 id 注册 `main` keyed 面板，渲染工作台外壳 `WorkbenchMain`；
 * 3. 声明本插件自己的会话作用域工厂 `workbench.node.chat`，由它渲染官方
 *    `conversation.content`（variant = 'embedded'），即面板内的真实 DSH 会话。
 *
 * 注册的局部槽位 `workbench.node.chat.conversation` 以会话引用为输入，
 * 组件里调用 `conversation.content` 工厂 —— 结构与官方 ui-subagent 的
 * `sidebar.chat.conversation` 一致，只是换成插件自己的工厂。
 */
import type { Context as ClientContext } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/dsh-client-locale/client'
import type { MainPanelId } from '@deepseek-ai/dsh-client-ui-layout/client'
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import type {} from '@deepseek-ai/dsh-client-ui-renderer/client'
import type {} from '@deepseek-ai/dsh-client-ui-sidebar/client'
import type {} from '@deepseek-ai/dsh-client-ui-workspace/client'
import type { SessionReference } from '@deepseek-ai/dsh-api-session-controller/client'
import { dicts, NS, type WorkbenchLocaleKey } from './locales.ts'
import { WorkbenchPanelIcon } from './main/WorkbenchPanelIcon.tsx'
import { WorkbenchMain, type WorkbenchMainInjected } from './main/WorkbenchMain.tsx'
import { NodeChatListConversation, type NodeChatInput } from './session/NodeSessionView.tsx'

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface LocaleNamespaceMap {
    /** 工作台面板自身的文案（外壳、会话视图的提示与错误态）。 */
    workbench: WorkbenchLocaleKey
  }

  interface SlotMap {
    /** 会话引用作用域：工作台面板内的嵌入会话。 */
    'workbench.node.chat.conversation': { kind: 'single'; scope: 'session' }
  }

  interface SlotFactoryMap {
    /** 嵌入一个已 retain 的 DSH 会话（工作台节点页的「会话」视图）。 */
    'workbench.node.chat': {
      scope: 'session'
      props: NodeChatInput
      children: { 'workbench.node.chat.conversation': { kind: 'single'; scope: 'session' } }
      slots: { conversation: { scope: 'session' } }
    }
  }
}

/** 侧栏入口与 `main` 面板共用同一个面板 id（官方 plugin-manager / ui-schedule 同款做法）。 */
export const PANEL_ID = 'workbench' as MainPanelId

/** 本插件 Client 半要求的服务：注册槽位、取文案、面板导航、会话与新建会话。 */
export const inject = ['slots', 'locale', 'layout', 'sessions', 'uiWorkspace']

/**
 * 注册工作台入口、面板与嵌入会话工厂。
 * @param ctx - 浏览器侧插件上下文。
 */
export function apply(ctx: ClientContext): void {
  ctx.effect(() => ctx.locale.register(NS, dicts), 'fde-workbench: dictionaries')
  const t = ctx.locale.bind(NS)

  // 嵌入会话工厂：输入是一个已 retain 的会话引用，内部用 SessionProvider 声明作用域后
  // 调用官方 conversation.content 工厂；局部槽位 conversation 固定渲染 Chat。
  ctx.effect(() => ctx.slots.registerFactory({
    name: 'workbench.node.chat',
    scope: 'session',
    children: {
      'workbench.node.chat.conversation': { kind: 'single', scope: 'session' },
    },
    slots: { conversation: { scope: 'session' } },
  }, NodeChatListConversation as (props: unknown) => React.ReactNode),
  'fde-workbench: embedded conversation factory')

  // 工作台整页：`main` 是 keyed 槽位，key 必须与侧栏入口 id 相同。
  // 所有跨服务依赖集中在 inject 里显式传入，组件本身不读浏览器全局。
  ctx.effect(() => ctx.slots.inject('main', () => ctx.slots.register({
    name: 'main',
    key: PANEL_ID,
    locale: NS,
    inject: (): WorkbenchMainInjected => ({
      layout: ctx.layout,
      sessionHost: { sessions: ctx.sessions },
      // 新建会话走官方导航动作（与侧栏「新建会话」同一条路径），不碰业务接口。
      createSession: () => { ctx.uiWorkspace.startSession() },
    }),
  }, WorkbenchMain)), 'fde-workbench: main panel')

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
export type { NodeChatInput, SessionReference }
