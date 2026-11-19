/**
 * 各业务模块的接口函数（与 docs/api.md 逐条对应）。
 *
 * 只做「路径 + 参数 + 返回类型」的声明，不写任何界面逻辑：
 * - 请求封装、JWT、错误码统一在 `api/client.ts`；
 * - 组件里不自拼 URL，一律调用这里的函数（路径变更只需改一处）。
 */
import { ApiClient, ApiError } from './client.ts'
import type {
  AdvancePreviewVO, LoginVO, MemberVO, NodeInputUploadVO, PageResult, ProjectVO, ProjectWriteRequest,
  RequirementCreateRequest, RequirementDetailVO, RequirementNodeVO, RequirementUpdateRequest, RequirementVO,
  SessionCreateRequest, SessionVO, SkillVO, UserVO, WorkflowCreateRequest, WorkflowNodesRequest, WorkflowSummaryVO,
  WorkflowUpdateRequest, WorkflowVO,
} from './types.ts'

/** 认证 `/auth`（api.md §2）。 */
export function createAuthApi(client: ApiClient) {
  return {
    /**
     * 登录。演示账号 demo / demo2 的密码为空字符串。
     * @param username - 用户名
     * @param password - 密码（可为空）
     */
    login: (username: string, password: string): Promise<LoginVO> =>
      client.json<LoginVO>('POST', '/auth/login', { username, password }),
    /** 当前登录用户（带 token 校验）。 */
    me: (): Promise<UserVO> => client.json<UserVO>('GET', '/auth/me'),
    /**
     * 注册（用户名 3–32 位字母数字下划线，密码可空）。
     * @param username - 用户名
     * @param password - 密码（可空）
     * @param displayName - 展示名（缺省等于用户名）
     */
    register: (username: string, password: string, displayName?: string): Promise<{ userId: number }> =>
      client.json<{ userId: number }>('POST', '/auth/register', { username, password, displayName }),
  }
}

/**
 * 去掉值为 undefined 的字段。
 *
 * 为什么需要：后端的 `description` 是可选字段，JSON 里出现 `"description": null` 与
 * 「字段缺失」在服务端语义不同（前者会显式清空）。这里统一把 undefined 剔除，
 * 保证「留空」就是「不发送该字段」。
 *
 * @param body - 请求体
 * @returns 去掉 undefined 字段的新对象
 */
function compact<T extends object>(body: T): T {
  const result: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(body)) {
    if (value !== undefined) result[key] = value
  }
  return result as T
}

/** 认证接口类型。 */
export type AuthApi = ReturnType<typeof createAuthApi>

/** 项目 `/projects`（api.md §3）。 */
export function createProjectApi(client: ApiClient) {
  return {
    /**
     * 我所属的项目（分页）。
     * @param keyword - 名称模糊词
     * @param page - 页码（从 1 开始）
     * @param size - 每页条数（最大 100）
     */
    list: (keyword?: string, page = 1, size = 100): Promise<PageResult<ProjectVO>> =>
      client.json<PageResult<ProjectVO>>('GET', `/projects${ApiClient.query({ keyword, page, size })}`),
    /** 项目详情。 */
    detail: (id: number): Promise<ProjectVO> => client.json<ProjectVO>('GET', `/projects/${id}`),
    /** 创建项目（创建人自动成为 owner）。 */
    create: (body: ProjectWriteRequest): Promise<ProjectVO> => client.json<ProjectVO>('POST', '/projects', compact(body)),
    /** 编辑项目（owner）。 */
    update: (id: number, body: ProjectWriteRequest & { revision: number }): Promise<ProjectVO> =>
      client.json<ProjectVO>('PUT', `/projects/${id}`, compact(body)),
    /** 删除项目（owner；revision 走查询参数）。 */
    remove: (id: number, revision: number): Promise<null> =>
      client.json<null>('DELETE', `/projects/${id}${ApiClient.query({ revision })}`),
  }
}

/** 项目接口类型。 */
export type ProjectApi = ReturnType<typeof createProjectApi>

/** 成员 `/projects/{id}/members`（api.md §4，本任务只读）。 */
export function createMemberApi(client: ApiClient) {
  return {
    /** 成员列表。 */
    list: (projectId: number): Promise<MemberVO[]> =>
      client.json<MemberVO[]>('GET', `/projects/${projectId}/members`),
  }
}

/** 成员接口类型。 */
export type MemberApi = ReturnType<typeof createMemberApi>

/** 技能 `/projects/{id}/skills`（api.md §5，本任务只读列表 + 技能包下载）。 */
export function createSkillApi(client: ApiClient) {
  return {
    /** 技能列表（含当前版本与引用数）。 */
    list: (projectId: number): Promise<SkillVO[]> =>
      client.json<SkillVO[]>('GET', `/projects/${projectId}/skills`),
    /** 下载某版本的技能包（zip）。 */
    downloadVersion: (skillId: number, versionNo: number, fallbackName: string): Promise<string> =>
      client.download(`/skills/${skillId}/versions/${versionNo}/download`, fallbackName),
  }
}

/** 技能接口类型。 */
export type SkillApi = ReturnType<typeof createSkillApi>

/** 流程 `/projects/{id}/workflows`、`/workflows/{id}`（api.md §6）。 */
export function createWorkflowApi(client: ApiClient) {
  return {
    /** 项目流程列表。 */
    list: (projectId: number): Promise<WorkflowSummaryVO[]> =>
      client.json<WorkflowSummaryVO[]>('GET', `/projects/${projectId}/workflows`),
    /** 创建流程（只收名称与说明，节点另存）。 */
    create: (projectId: number, body: WorkflowCreateRequest): Promise<WorkflowSummaryVO> =>
      client.json<WorkflowSummaryVO>('POST', `/projects/${projectId}/workflows`, compact(body)),
    /** 流程详情（含全部节点、输入输出项与技能）。 */
    detail: (id: number): Promise<WorkflowVO> => client.json<WorkflowVO>('GET', `/workflows/${id}`),
    /** 更新名称 / 说明（revision 原样回传）。 */
    update: (id: number, body: WorkflowUpdateRequest): Promise<WorkflowVO> =>
      client.json<WorkflowVO>('PUT', `/workflows/${id}`, compact(body)),
    /** 删除流程（被需求引用时 409/10403）。 */
    remove: (id: number, revision: number): Promise<null> =>
      client.json<null>('DELETE', `/workflows/${id}${ApiClient.query({ revision })}`),
    /**
     * 整体保存节点列表：必须提交**完整**节点列表（含未改动的节点），
     * 顺序即执行顺序；`revision` 原样回传（毫秒时间戳，不解析、不 +1）。
     */
    saveNodes: (id: number, body: WorkflowNodesRequest): Promise<WorkflowVO> =>
      client.json<WorkflowVO>('PUT', `/workflows/${id}/nodes`, body),
  }
}

/** 流程接口类型。 */
export type WorkflowApi = ReturnType<typeof createWorkflowApi>

/** 需求 `/projects/{id}/requirements`、`/requirements/{id}`（api.md §7）。 */
export function createRequirementApi(client: ApiClient) {
  return {
    /**
     * 项目需求列表。
     * @param projectId - 项目 ID
     * @param keyword - 标题模糊词
     * @param status - in_progress / completed
     */
    list: (projectId: number, keyword?: string, status?: string): Promise<PageResult<RequirementVO>> =>
      client.json<PageResult<RequirementVO>>(
        'GET', `/projects/${projectId}/requirements${ApiClient.query({ keyword, status, page: 1, size: 100 })}`,
      ),
    /** 创建需求（必须带 workflowId；同事务生成节点实例）。 */
    create: (projectId: number, body: RequirementCreateRequest): Promise<RequirementDetailVO> =>
      client.json<RequirementDetailVO>('POST', `/projects/${projectId}/requirements`, compact(body)),
    /** 需求详情（需求本体 + 节点进度 + 权限）。 */
    detail: (id: number): Promise<RequirementDetailVO> =>
      client.json<RequirementDetailVO>('GET', `/requirements/${id}`),
    /** 编辑标题 / 说明（创建人或 owner；revision 原样回传）。 */
    update: (id: number, body: RequirementUpdateRequest): Promise<RequirementDetailVO> =>
      client.json<RequirementDetailVO>('PUT', `/requirements/${id}`, compact(body)),
    /** 删除需求（revision 走查询参数）。 */
    remove: (id: number, revision: number): Promise<null> =>
      client.json<null>('DELETE', `/requirements/${id}${ApiClient.query({ revision })}`),
  }
}

/** 需求接口类型。 */
export type RequirementApi = ReturnType<typeof createRequirementApi>

/** 节点 `/requirements/{rid}/nodes/{nid}`（api.md §8）。 */
export function createNodeApi(client: ApiClient) {
  return {
    /** 节点信息页数据。 */
    detail: (requirementId: number, nodeId: number): Promise<RequirementNodeVO> =>
      client.json<RequirementNodeVO>('GET', `/requirements/${requirementId}/nodes/${nodeId}`),
    /**
     * 上传节点输入（multipart）。
     * @param requirementId - 需求 ID
     * @param nodeId - 节点实例 ID
     * @param file - 文件
     * @param inputItemId - 固定输入项 ID；自由输入节点必须不传
     */
    uploadInput: (requirementId: number, nodeId: number, file: File, inputItemId: number | null): Promise<NodeInputUploadVO> => {
      const form = new FormData()
      form.append('file', file)
      if (inputItemId !== null) form.append('inputItemId', String(inputItemId))
      return client.upload<NodeInputUploadVO>(`/requirements/${requirementId}/nodes/${nodeId}/inputs`, form)
    },
    /** 删除已上传的输入文件。 */
    deleteInput: (requirementId: number, nodeId: number, inputRecordId: number): Promise<null> =>
      client.json<null>('DELETE', `/requirements/${requirementId}/nodes/${nodeId}/inputs/${inputRecordId}`),
    /** 推进条件预览（缺哪些必填输出 / 输入）。 */
    advancePreview: (requirementId: number, nodeId: number): Promise<AdvancePreviewVO> =>
      client.json<AdvancePreviewVO>('GET', `/requirements/${requirementId}/nodes/${nodeId}/advance-preview`),
    /** 下载节点相关文件（输入原件、上一节点输出、输出项）。 */
    downloadFile: (fileId: number, fallbackName: string): Promise<string> =>
      client.download(`/files/${fileId}/download`, fallbackName),
  }
}

/** 节点接口类型。 */
export type NodeApi = ReturnType<typeof createNodeApi>

/** 会话 `/requirements/{rid}/sessions`、`/sessions/{id}/touch`（api.md §9）。 */
export function createSessionApi(client: ApiClient) {
  return {
    /** 需求下的全部会话（按 lastActiveAt 倒序）。 */
    list: (requirementId: number): Promise<SessionVO[]> =>
      client.json<SessionVO[]>('GET', `/requirements/${requirementId}/sessions`),
    /** 登记新建会话并绑定节点。 */
    create: (requirementId: number, body: SessionCreateRequest): Promise<SessionVO> =>
      client.json<SessionVO>('POST', `/requirements/${requirementId}/sessions`, compact(body)),
    /** 更新最后活跃时间，并在指定节点时将此会话设为该节点的默认。 */
    touch: (sessionId: number, nodeId?: number): Promise<SessionVO> =>
      client.json<SessionVO>('POST', `/sessions/${sessionId}/touch`, nodeId === undefined ? undefined : { nodeId }),
  }
}

/** 会话接口类型。 */
export type SessionApi = ReturnType<typeof createSessionApi>

/** 全部业务接口的集合（在 `apply()` 里创建一次，注入给页面）。 */
export interface WorkbenchApi {
  readonly client: ApiClient
  readonly auth: AuthApi
  readonly project: ProjectApi
  readonly member: MemberApi
  readonly skill: SkillApi
  readonly workflow: WorkflowApi
  readonly requirement: RequirementApi
  readonly node: NodeApi
  readonly session: SessionApi
}

/**
 * 组装全部接口。
 * @param client - 已构造的请求封装（需要先 `setToken`）。
 * @returns 各模块接口表。
 */
export function createWorkbenchApi(client: ApiClient): WorkbenchApi {
  return {
    client,
    auth: createAuthApi(client),
    project: createProjectApi(client),
    member: createMemberApi(client),
    skill: createSkillApi(client),
    workflow: createWorkflowApi(client),
    requirement: createRequirementApi(client),
    node: createNodeApi(client),
    session: createSessionApi(client),
  }
}

/** 供页面判断错误类型。 */
export { ApiError }
