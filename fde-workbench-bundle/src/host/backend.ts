/**
 * 后端「内部接口」（docs/api.md §11）的类型与调用封装。
 *
 * 为什么单独一层：四个工具都要「从会话 cwd 解析需求 → 拿节点快照 → 调后端」，
 * 把这部分收敛在这里，工具本身只剩参数校验与结果渲染；同时 HTTP 失败在这一层
 * 就翻译成**可读中文**（工具层不再抛裸异常）。
 *
 * 鉴权：请求头 `X-Internal-Token`，值来自插件 `Config.internalToken`
 * （对应后端 `fde.workbench.internal-token`，环境变量 `FDE_INTERNAL_TOKEN`）。
 */

import { parseWorkspaceCwd } from './paths.ts'

/** 内部接口的字段名逐字对齐 docs/api.md §11 与后端 `NodeContextVO`。 */

/** 生效技能（名称 + 快照版本号）。 */
export interface SkillRef {
  name: string
  versionNo?: number | null
}

/** 文件引用（相对 workspace 的路径）。 */
export interface FileRef {
  fileId: number
  name: string
  relativePath: string
  sizeBytes: number
  sha256: string
}

/** 固定输入项及已上传文件。 */
export interface InputFileRef {
  itemId: number
  name: string
  fileKind: string
  required: boolean
  files: FileRef[]
}

/** 输出项及完成状态。 */
export interface OutputItemRef {
  itemKey: string
  name: string
  fileKind: string
  required: boolean
  submitted: boolean
  versionNo?: number | null
  fileName?: string | null
  relativePath?: string | null
  suggestPath: string
}

/** 上一节点输出项。 */
export interface PreviousOutput {
  itemKey: string
  name: string
  relativePath: string
  fileId: number
}

/** 上一节点。 */
export interface PreviousNode {
  seq: number
  name: string
  outputs: PreviousOutput[]
}

/** `GET /internal/nodes/{nid}/context` 的 data。 */
export interface NodeContext {
  nodeInstanceId: number
  requirementId: number
  requirementTitle: string
  requirementStatus: string
  workspacePath: string
  nodeSeq: number
  nodeCount: number
  nodeName: string
  nodeDescription?: string | null
  nodeStatus: string
  inputType: string
  readonly: boolean
  skills: SkillRef[]
  inputItems: InputFileRef[]
  freeInputFiles: FileRef[]
  outputs: OutputItemRef[]
  previousNode?: PreviousNode | null
  outputDir: string
  nextNodeName?: string | null
}

/** 一个节点实例的定位摘要（`GET /internal/requirements/{rid}/nodes` 的 nodes[]）。 */
export interface NodeBrief {
  nodeInstanceId: number
  seq: number
  name: string
  status: string
}

/** `GET /internal/requirements/{rid}/nodes` 的 data：需求信息 + 全部节点摘要（按 seq 升序）。 */
export interface RequirementNodes {
  requirementId: number
  requirementTitle: string
  requirementStatus: string
  currentSeq: number
  nodes: NodeBrief[]
}

/** `POST /internal/nodes/{nid}/outputs/{itemKey}/commit` 的 data。 */
export interface OutputCommit {
  committed: boolean
  versionNo: number
  advanced: boolean
  nextNodeName?: string | null
  requirementCompleted: boolean
  remainingRequiredOutputs: string[]
  remainingRequiredInputs: number[]
}

/** `POST /internal/nodes/{nid}/reference` 的 data。 */
export interface ReferenceRecord {
  recorded: boolean
  fileId: number
  fileName: string
  kind: string
}

/** 后端的统一响应外壳（code = 0 表示成功）。 */
interface ApiEnvelope<T> {
  code: number
  message?: string
  data?: T
}

/** 可读的后端错误：message 已经是给模型/用户看的中文。 */
export class BackendError extends Error {
  /** 后端业务码（HTTP 层失败时用 HTTP 状态码）。 */
  readonly code: number

  /** HTTP 状态码（网络层失败时为 0）。 */
  readonly status: number

  constructor(message: string, code: number, status: number) {
    super(message)
    this.name = 'BackendError'
    this.code = code
    this.status = status
  }
}

/** 解析后的节点定位：会话里问的是哪个需求、真实 workspace 在哪。 */
export interface ResolvedNode {
  /** 后端返回的节点上下文（权威快照）。 */
  context: NodeContext
  /** workspace 绝对路径（以后端为准）。 */
  workspacePath: string
  /** 需求 ID（来自会话 cwd，已与后端交叉核对）。 */
  requirementId: number
}

/** 内部接口客户端配置。 */
export interface BackendClientOptions {
  /** Java 后端根地址，如 `http://127.0.0.1:8081`。 */
  javaBaseUrl: string
  /** `X-Internal-Token` 的取值。 */
  internalToken: string
}

/**
 * 内部接口客户端：只做「发请求 + 解包 + 翻译错误」，不含业务规则。
 */
export class BackendClient {
  private readonly baseUrl: string
  private readonly token: string

  constructor(options: BackendClientOptions) {
    // 去掉尾部斜杠，避免出现 `//internal/...`
    this.baseUrl = options.javaBaseUrl.replace(/\/+$/, '')
    this.token = options.internalToken
  }

  /**
   * 取节点上下文。
   * @param nodeInstanceId - 节点实例 ID
   * @param signal - 取消信号
   * @returns 节点上下文
   */
  async nodeContext(nodeInstanceId: number, signal?: AbortSignal): Promise<NodeContext> {
    return await this.request<NodeContext>(
      'GET',
      `/internal/nodes/${nodeInstanceId}/context?operator=agent`,
      undefined,
      signal,
    )
  }

  /** 读取当前节点指定快照位置与版本的技能入口。 */
  async nodeSkillEntry(
    requirementId: number,
    nodeInstanceId: number,
    position: number,
    versionNo: number,
    signal?: AbortSignal,
  ): Promise<{ content: string }> {
    return await this.request<{ content: string }>(
      'GET',
      `/internal/requirements/${requirementId}/nodes/${nodeInstanceId}/skills/${position}?versionNo=${versionNo}`,
      undefined,
      signal,
    )
  }

  /**
   * 取某个需求下全部节点实例的定位摘要。
   *
   * 为什么需要它：`/context` 只接受节点实例 ID，而模型调用 `node_context_get` 时可以不传；
   * 有了这个接口，"当前节点是哪个"由后端一次回答，插件不必再按节点 ID 逐个试探。
   * @param requirementId - 需求 ID（来自会话 cwd `<storageRoot>/ws/<requirementId>`）
   * @param signal - 取消信号
   * @returns 需求信息与节点摘要（按 seq 升序）
   */
  async requirementNodes(requirementId: number, signal?: AbortSignal): Promise<RequirementNodes> {
    return await this.request<RequirementNodes>(
      'GET',
      `/internal/requirements/${requirementId}/nodes`,
      undefined,
      signal,
    )
  }

  /**
   * 登记输出项（后端负责校验 sha256、复制副本、判定推进）。
   * @param nodeInstanceId - 节点实例 ID
   * @param itemKey - 输出项稳定键
   * @param body - `{ relativePath, sha256, operator }`
   * @param signal - 取消信号
   * @returns 后端提交结果
   */
  async commitOutput(
    nodeInstanceId: number,
    itemKey: string,
    body: { relativePath: string; sha256: string; operator: string },
    signal?: AbortSignal,
  ): Promise<OutputCommit> {
    return await this.request<OutputCommit>(
      'POST',
      `/internal/nodes/${nodeInstanceId}/outputs/${encodeURIComponent(itemKey)}/commit`,
      body,
      signal,
    )
  }

  /**
   * 记录「模型读了某引用文件」（用于页面展示"模型读了什么"）。
   * @param nodeInstanceId - 节点实例 ID
   * @param body - `{ kind, fileId, itemKey?, operator }`
   * @param signal - 取消信号
   * @returns 后端登记结果
   */
  async reference(
    nodeInstanceId: number,
    body: { kind: string; fileId: number; itemKey?: string; operator: string },
    signal?: AbortSignal,
  ): Promise<ReferenceRecord> {
    return await this.request<ReferenceRecord>(
      'POST',
      `/internal/nodes/${nodeInstanceId}/reference`,
      body,
      signal,
    )
  }

  /**
   * 统一请求入口：所有失败都翻译成 {@link BackendError}（中文 message），
   * 不把 fetch 的原始异常抛给工具层。
   */
  private async request<T>(
    method: 'GET' | 'POST',
    path: string,
    body: unknown,
    signal?: AbortSignal,
  ): Promise<T> {
    // 组合外部取消信号（用户按 Esc Esc 停止）与 15 秒超时：任一触发即中止请求
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(new Error('timeout')), 15_000)
    const onAbort = (): void => controller.abort(signal?.reason)
    signal?.addEventListener('abort', onAbort, { once: true })
    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        method,
        headers: {
          'X-Internal-Token': this.token,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json; charset=utf-8' }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: controller.signal,
      })
    } catch (error) {
      if (signal?.aborted === true) throw new BackendError('后端调用已取消。', 0, 0)
      const reason = error instanceof Error ? error.message : String(error)
      throw new BackendError(
        `无法连接工作台后端（${this.baseUrl}）：${reason}。请确认 Java 后端已启动，且插件 Config.javaBaseUrl 正确。`,
        0,
        0,
      )
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
    }

    const text = await response.text()
    let envelope: ApiEnvelope<T> | undefined
    try {
      envelope = text.length === 0 ? undefined : (JSON.parse(text) as ApiEnvelope<T>)
    } catch {
      envelope = undefined
    }

    // 401 是"令牌没配对"的典型症状。只在确实是令牌问题时给那句提示：
    // /internal/** 的 401 都带 10103（InternalAuthFilter），但业务接口自己也可能返回 401/10101，
    // 那种情况应原样透出后端的原因，而不是误导用户去改令牌。
    if (response.status === 401 && (envelope === undefined || envelope.code === 10103)) {
      const detail = envelope?.message ?? text.slice(0, 200)
      throw new BackendError(
        `后端拒绝了内部接口调用（401）：${detail}。请检查插件 internalToken 与后端 FDE_INTERNAL_TOKEN 是否一致。`,
        10103,
        401,
      )
    }
    if (!response.ok || envelope === undefined) {
      const detail = envelope?.message ?? text.slice(0, 300)
      throw new BackendError(
        `后端返回错误（HTTP ${response.status}）：${detail || '无响应内容'}`,
        envelope?.code ?? response.status,
        response.status,
      )
    }
    if (envelope.code !== 0) {
      throw new BackendError(envelope.message ?? `后端业务错误 ${envelope.code}`, envelope.code, response.status)
    }
    if (envelope.data === undefined) {
      throw new BackendError('后端响应缺少 data 字段。', response.status, response.status)
    }
    return envelope.data
  }
}

/**
 * 解析节点上下文：会话 cwd → requirementId → 后端 `GET /context`，并交叉核对两边。
 * @param client - 内部接口客户端
 * @param params - 会话 cwd、存储根、模型显式给出的参数
 * @param signal - 取消信号
 * @returns 解析结果（workspace 路径以后端为准）
 * @throws Error 中文可读错误
 */
export async function resolveNodeContext(
  client: BackendClient,
  params: { cwd: string | undefined; storageRoot: string; requirementId?: number; nodeInstanceId?: number },
  signal?: AbortSignal,
): Promise<ResolvedNode> {
  const fromCwd = parseWorkspaceCwd(params.cwd, params.storageRoot)
  const requirementId = params.requirementId ?? fromCwd?.requirementId
  if (requirementId === undefined) {
    throw new Error(
      '当前会话目录不属于需求 workspace，无法确定需求。'
      + `请确认会话工作目录是 <存储根>/ws/<需求ID>（当前：${params.cwd ?? '未知'}）。`,
    )
  }
  if (params.nodeInstanceId === undefined) {
    throw new Error('缺少 nodeInstanceId：请先调用 node_context_get 拿到当前节点的 nodeInstanceId。')
  }
  const context = await client.nodeContext(params.nodeInstanceId, signal)
  if (Number(context.requirementId) !== Number(requirementId)) {
    throw new Error(
      `节点 ${params.nodeInstanceId} 属于需求 ${context.requirementId}，与当前会话所在需求 ${requirementId} 不一致。`,
    )
  }
  return {
    context,
    requirementId,
    workspacePath: context.workspacePath,
  }
}
