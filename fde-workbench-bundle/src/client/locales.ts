/**
 * 工作台文案字典（全部界面文案的唯一来源）。
 *
 * 与 `locale/zh.json`、`locale/en.json` 保持同一 key 集合：那两个 JSON 供
 * DSH 插件管理器读取 `meta.title` / `meta.description`（插件列表与详情页），
 * 这里的字典供运行期 UI 使用（它们被打进 client bundle，不依赖文件读取）。
 */

/** 字典命名空间。 */
export const NS = 'workbench'

/** 简体中文字典（key 集合的权威来源）。 */
export const zh = {
  'panel.label': 'Web 工作台',
  'workbench.title': 'Web 工作台',
  'workbench.back': '返回',
  'workbench.backHint': '退出工作台面板，回到当前会话',
  'workbench.placeholder': '工作台外壳已就绪。项目中心、需求与节点页面将在后续阶段接入。',
  'workbench.smokeHint': '下方嵌入的是一个真实 DSH 会话，用于验证会话嵌入链路。',
  'session.heading': '节点会话',
  'session.source': '会话来源：{value}',
  'session.sourceSelected': '当前选中的会话',
  'session.sourceRecent': '最近活跃的会话',
  'session.loading': '正在读取会话…',
  'session.empty': '当前没有可用的 DSH 会话。',
  'session.error': '会话加载失败：{message}',
  'session.retry': '重试',
  'session.create': '新建会话',
  'session.reload': '刷新会话列表',
  'session.missing': '找不到可嵌入的会话。请新建一个会话后重试。',
  'session.emptyHint': '面板内没有可嵌入的会话；新建一个会话后即可在这里继续对话。',
  'session.switch': '切换到其它会话',
  'session.noSession': '（未选择）',
} as const

/** 工作台命名空间的 key 域。 */
export type WorkbenchLocaleKey = keyof typeof zh

/** 英文字典，key 与中文权威来源一致。 */
export const en: Record<WorkbenchLocaleKey, string> = {
  'panel.label': 'Web Workbench',
  'workbench.title': 'Web Workbench',
  'workbench.back': 'Back',
  'workbench.backHint': 'Leave the workbench panel and return to the current session',
  'workbench.placeholder': 'The workbench shell is ready. Project center, requirement, and node pages arrive in later stages.',
  'workbench.smokeHint': 'The session below is a real DSH conversation, used to verify the embedding path.',
  'session.heading': 'Node session',
  'session.source': 'Session source: {value}',
  'session.sourceSelected': 'the session selected in the sidebar',
  'session.sourceRecent': 'the most recently active session',
  'session.loading': 'Loading the session…',
  'session.empty': 'No DSH session is available.',
  'session.error': 'The session failed to load: {message}',
  'session.retry': 'Retry',
  'session.create': 'New session',
  'session.reload': 'Refresh session list',
  'session.missing': 'No embeddable session was found. Create a new session and try again.',
  'session.emptyHint': 'There is no session to embed here; create one and the conversation continues in this panel.',
  'session.switch': 'Switch session',
  'session.noSession': '(none selected)',
}

/** 注册到 `ctx.locale` 的字典集合。 */
export const dicts = { zh, en }
