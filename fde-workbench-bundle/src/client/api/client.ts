/**
 * Java 后端 fetch 封装：base URL、JWT 注入、统一错误码处理、文件上传与下载。
 *
 * 设计要点（docs/architecture.md §2 L1、§8 失败与降级）：
 * - 所有 `/api/**` 请求都必须带 `Authorization: Bearer <JWT>`；token 只在内存里（`setToken`），
 *   不写 localStorage：DSH 页面刷新后重新登录即可，避免 token 长期滞留在浏览器存储。
 * - 业务错误统一返回 `{code, message, data}`（HTTP 可能仍是 200/400/403…），
 *   因此 `code !== 0` 一律抛 `ApiError`，由页面渲染可读文案；`401/10101` 额外触发登出回调（回到登录页）。
 * - 后端未启动（fetch 直接抛 TypeError）时抛出「不可达」错误，页面据此显示可重试错误态，绝不白屏。
 * - 文件下载不能用 `<a download>` 直接指向接口（带不了 JWT），必须 fetch 成 blob 再用临时链接触发保存。
 */

/** 后端未启动 / 网络不可达时的错误码（不是后端返回码，仅前端内部使用）。 */
export const CODE_UNREACHABLE = -1

/** 未登录或 token 过期（api.md §1.3）。 */
export const CODE_UNAUTHORIZED = 10101

/** 参数校验失败（api.md §1.3）。 */
export const CODE_PARAM_INVALID = 10001

/** 乐观锁冲突。 */
export const CODE_REVISION_CONFLICT = 10402

/** 被引用，不能删除。 */
export const CODE_REFERENCED = 10403

/** 统一响应体。 */
interface Envelope<T> {
  readonly code: number
  readonly message: string
  readonly data: T
}

/**
 * 带业务码的接口错误。
 */
export class ApiError extends Error {
  /** 业务错误码；`CODE_UNREACHABLE` 表示网络层失败 */
  readonly code: number
  /** HTTP 状态码；网络层失败为 0 */
  readonly status: number

  /** 业务错误附带的结构化详情，例如删除受阻时的引用清单。 */
  readonly data: unknown

  constructor(code: number, message: string, status = 0, data?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.data = data
  }
}

/**
 * 后端地址的读取顺序：
 * 1. 显式注入：`globalThis.FDE_WORKBENCH_API_BASE`（部署到别的端口，或前面挂了同源反向代理时可用）；
 * 2. 缺省：`http://127.0.0.1:8081`（docs/api.md 的 base_url）。
 *
 * 注意：这里**不能**用 `import.meta.env` —— 插件 bundle 的产物格式是 CJS（`lib/client.js` 由 DSH 的
 * 模块加载器执行），rolldown 会把 `import.meta` 换成空对象并给出 EMPTY_IMPORT_META 警告。
 * @returns 去掉尾部斜杠的 base URL。
 */
function resolveBaseUrl(): string {
  const injected = (globalThis as { FDE_WORKBENCH_API_BASE?: unknown }).FDE_WORKBENCH_API_BASE
  // 约定：注入值就是完整的 `/api` 前缀本身（例如 `http://127.0.0.1:3040/fde-api`），
  // 下面的路径会直接拼在它后面（`/auth/login`、`/projects` …），所以这里只去掉尾部斜杠。
  if (typeof injected === 'string' && injected.trim() !== '') return injected.replace(/\/+$/, '')
  return 'http://127.0.0.1:8081'
}

/**
 * 接口客户端：一个实例在 `apply()` 里创建、通过注入参数传给所有页面组件。
 */
export class ApiClient {
  /** 后端基础地址（无尾部斜杠） */
  readonly baseUrl: string
  private token: string | undefined
  private unauthorizedHandler: (() => void) | undefined

  constructor(baseUrl: string = resolveBaseUrl()) {
    this.baseUrl = baseUrl
  }

  /**
   * 设置 / 清除 JWT。
   * @param token - 登录返回的 JWT；`undefined` 表示登出。
   */
  setToken(token: string | undefined): void {
    this.token = token
  }

  /**
   * 注册 401 处理（回到登录页）。
   * @param handler - 回调；多次注册以最后一次为准。
   */
  onUnauthorized(handler: (() => void) | undefined): void {
    this.unauthorizedHandler = handler
  }

  /**
   * 构造请求头（含 JWT）。
   * @param extra - 额外请求头。
   * @returns 请求头对象。
   */
  private headers(extra?: Record<string, string>): Record<string, string> {
    const headers: Record<string, string> = { ...extra }
    if (this.token !== undefined) headers['Authorization'] = `Bearer ${this.token}`
    return headers
  }

  /**
   * 发起请求并解析统一响应体。
   * @param path - 以 `/` 开头的接口路径（含查询串）。
   * @param init - fetch 参数。
   * @returns 业务数据（`data` 字段）。
   * @throws ApiError - 网络失败、HTTP 非 2xx、业务码非 0。
   */
  async request<T>(path: string, init?: RequestInit): Promise<T> {
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, init)
    } catch (error) {
      throw new ApiError(CODE_UNREACHABLE, error instanceof Error ? error.message : String(error), 0)
    }
    // 后端异常时可能返回非 JSON（例如容器层的 500 页面），解析失败要给出可读文案而不是抛 SyntaxError
    let envelope: Envelope<T> | undefined
    try {
      envelope = await response.json() as Envelope<T>
    } catch {
      envelope = undefined
    }
    if (envelope === undefined || typeof envelope.code !== 'number') {
      throw new ApiError(
        envelope?.code ?? CODE_UNREACHABLE,
        `HTTP ${response.status}${response.statusText === '' ? '' : ` ${response.statusText}`}`,
        response.status,
      )
    }
    if (envelope.code !== 0) {
      // 401/10101 = 未登录或过期：通知上层跳回登录页（页面不在这里自行跳转）
      if (envelope.code === CODE_UNAUTHORIZED || response.status === 401) this.unauthorizedHandler?.()
      throw new ApiError(envelope.code, envelope.message, response.status, envelope.data)
    }
    return envelope.data
  }

  /**
   * JSON 请求（GET / POST / PUT / DELETE）。
   * @param method - HTTP 方法。
   * @param path - 接口路径。
   * @param body - 请求体（会 JSON 序列化）；GET/DELETE 不传。
   */
  json<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown): Promise<T> {
    const init: RequestInit = { method, headers: this.headers() }
    if (body !== undefined) {
      init.body = JSON.stringify(body)
      ;(init.headers as Record<string, string>)['Content-Type'] = 'application/json'
    }
    return this.request<T>(path, init)
  }

  /**
   * 查询串：跳过空值。
   * @param params - 键值表。
   * @returns `?a=1&b=2` 或空串。
   */
  static query(params: Record<string, string | number | undefined | null>): string {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined || value === null || value === '') continue
      search.set(key, String(value))
    }
    const text = search.toString()
    return text === '' ? '' : `?${text}`
  }

  /**
   * multipart 上传（节点输入、技能包）。
   * 注意不手写 Content-Type：浏览器要自己补 boundary。
   * @param path - 接口路径。
   * @param form - FormData。
   */
  upload<T>(path: string, form: FormData): Promise<T> {
    return this.request<T>(path, { method: 'POST', headers: this.headers(), body: form })
  }

  /**
   * 下载文件（带 JWT 的 fetch → blob → 触发浏览器保存）。
   *
   * 为什么不能直接把按钮做成 `<a href="/api/files/{id}/download" download>`：
   * 浏览器导航不会带上插件的 `Authorization` 头，后端会返回 401，页面只会跳到一段错误 JSON。
   * 所以这里用 fetch 拿字节，再拿 `Content-Disposition` 里的文件名（RFC 5987 `filename*=UTF-8''…`）存盘。
   *
   * @param path - 下载接口路径。
   * @param fallbackName - 响应头没有文件名时使用的文件名。
   * @returns 实际保存的文件名。
   * @throws ApiError - 失败时（包括后端返回的 JSON 错误体）。
   */
  async download(path: string, fallbackName: string): Promise<string> {
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, { headers: this.headers() })
    } catch (error) {
      throw new ApiError(CODE_UNREACHABLE, error instanceof Error ? error.message : String(error), 0)
    }
    const contentType = response.headers.get('content-type') ?? ''
    if (!response.ok || contentType.includes('application/json')) {
      // 出错时后端给的是统一 JSON 错误体：读出来抛 ApiError，让页面显示后端文案
      let envelope: Envelope<unknown> | undefined
      try {
        envelope = await response.json() as Envelope<unknown>
      } catch {
        envelope = undefined
      }
      throw new ApiError(envelope?.code ?? CODE_UNREACHABLE,
        envelope?.message ?? `HTTP ${response.status}`, response.status)
    }
    const blob = await response.blob()
    const name = filenameOf(response.headers.get('content-disposition')) ?? fallbackName
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = name
    anchor.style.display = 'none'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    // 立刻 revoke 会让部分浏览器的下载被中断，延迟释放
    window.setTimeout(() => { URL.revokeObjectURL(url) }, 10_000)
    return name
  }
}

/**
 * 从 `Content-Disposition` 头解析文件名（优先 RFC 5987 的 `filename*`）。
 * @param header - 响应头值。
 * @returns 文件名；解析不到返回 undefined。
 */
export function filenameOf(header: string | null): string | undefined {
  if (header === null) return undefined
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (extended?.[1] !== undefined) {
    try {
      return decodeURIComponent(extended[1])
    } catch {
      // 编码非法时退回普通 filename
    }
  }
  const plain = /filename="([^"]+)"/i.exec(header)
  return plain?.[1]
}

/**
 * 把任意异常转成可展示的一行文案。
 * @param error - 任意异常。
 * @returns 文案（ApiError 用后端 message）。
 */
export function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return error instanceof Error ? error.message : String(error)
}

/**
 * 判断是否「后端不可达」错误（页面据此显示"确认后端已启动"的提示）。
 * @param error - 任意异常。
 */
export function isUnreachable(error: unknown): boolean {
  return error instanceof ApiError && error.code === CODE_UNREACHABLE
}
