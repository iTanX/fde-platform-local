/**
 * 业务接口出网结构（TypeScript 类型）。
 *
 * 与 `docs/api.md §1.4` 及 `workbench-api` 的 VO 记录一一对应（已核对 Java record 字段）。
 * 约定：
 * - 所有字段按后端 JSON 原样声明（可空字段用 `| null` 或可选）；
 * - 时间字段是带 `+08:00` 偏移的字符串（`TimeFormats`），前端只做展示，不做时区换算；
 * - **revision 是后端原样回传的版本号**：需求的 revision 是从 1 开始的小整数，
 *   而流程的 revision 是 `updated_at` 的**毫秒时间戳**（long）。前端一律当不透明值原样保存 / 回传，
 *   既不按 int 解析，也不展示，更不自己 +1。
 */

/** 分页结果（api.md §1.2）。 */
export interface PageResult<T> {
  readonly items: T[]
  readonly total: number
  readonly page: number
  readonly size: number
}

/** 用户（api.md UserVO）。 */
export interface UserVO {
  readonly id: number
  readonly username: string
  readonly displayName: string
}

/** 登录结果（api.md §2）。 */
export interface LoginVO {
  readonly token: string
  readonly expiresAt: string
  readonly user: UserVO
}

/** 项目（api.md ProjectVO）。 */
export interface ProjectVO {
  readonly id: number
  readonly name: string
  readonly description: string | null
  readonly ownerId: number
  readonly memberCount: number
  readonly requirementCount: number
  /** 项目 revision 是小整数；原样回传 */
  readonly revision: number
  readonly createdAt: string
}

/** 项目成员（api.md MemberVO）。 */
export interface MemberVO {
  readonly userId: number
  readonly username: string
  readonly displayName: string
  readonly role: 'owner' | 'member' | string
  readonly joinedAt: string
}

/** 技能（api.md SkillVO）。 */
export interface SkillVO {
  readonly id: number
  readonly name: string
  readonly description: string | null
  readonly currentVersionId: number | null
  readonly currentVersionNo: number | null
  readonly usageCount: number
  readonly createdBy: number
  readonly createdAt: string
}

/** 流程技能引用（NodeVO.skills 项）。 */
export interface NodeSkillRefVO {
  readonly skillId: number
  readonly name: string
  readonly seq: number
}

/** 固定输入项（NodeVO.inputs 项）。 */
export interface NodeInputItemVO {
  readonly itemId: number
  readonly seq: number
  readonly name: string
  readonly description: string | null
  readonly fileKind: string
  readonly required: boolean
}

/** 输出项（NodeVO.outputs 项）。 */
export interface NodeOutputItemVO {
  readonly itemId: number
  readonly seq: number
  readonly itemKey: string
  readonly name: string
  readonly description: string | null
  readonly fileKind: string
  readonly required: boolean
}

/** 流程节点（api.md NodeVO）。 */
export interface NodeVO {
  readonly id: number | null
  readonly seq: number
  readonly name: string
  readonly description: string | null
  /** free = 自由输入；fixed = 固定输入 */
  readonly inputType: 'free' | 'fixed' | string
  readonly skills: NodeSkillRefVO[]
  readonly inputs: NodeInputItemVO[]
  readonly outputs: NodeOutputItemVO[]
}

/** 流程列表项（api.md WorkflowVO 的列表形态）。 */
export interface WorkflowSummaryVO {
  readonly id: number
  readonly projectId: number
  readonly name: string
  readonly description: string | null
  readonly nodeCount: number
  /** 毫秒时间戳，不透明值 */
  readonly revision: number
  readonly createdBy: number
  readonly createdAt: string
  readonly updatedAt: string
}

/** 流程详情（api.md WorkflowVO）。 */
export interface WorkflowVO extends WorkflowSummaryVO {
  readonly nodes: NodeVO[]
}

/** 需求（api.md RequirementVO）。 */
export interface RequirementVO {
  readonly id: number
  readonly projectId: number
  readonly workflowId: number
  readonly workflowName: string | null
  readonly title: string
  readonly description: string | null
  readonly creatorId: number
  readonly status: 'in_progress' | 'completed' | string
  readonly currentSeq: number
  readonly nodeCount: number
  readonly doneNodeCount: number
  /** 小整数版本号；原样回传 */
  readonly revision: number
  readonly editable: boolean
  readonly createdAt: string
  readonly updatedAt: string
}

/** 需求节点进度（RequirementDetailVO.nodes 项）。 */
export interface NodeProgressVO {
  readonly id: number
  readonly seq: number
  readonly name: string
  readonly status: 'waiting' | 'current' | 'done' | string
  readonly startedAt: string | null
  readonly completedAt: string | null
}

/** 需求权限（RequirementDetailVO.permissions）。 */
export interface RequirementPermissionsVO {
  /** 当前登录人能否操作：需求创建人或项目 owner，且需求进行中 */
  readonly canOperate: boolean
  readonly isCreator: boolean
}

/** 需求详情（api.md §7 GET）。 */
export interface RequirementDetailVO {
  readonly requirement: RequirementVO
  readonly nodes: NodeProgressVO[]
  readonly permissions: RequirementPermissionsVO
}

/** 文件（节点 VO 内的 FileVO）。 */
export interface FileVO {
  readonly fileId: number
  readonly originalName: string
  readonly sizeBytes: number
  readonly uploadedAt: string | null
  /** 仅输入文件有：输入记录 ID（删除输入时用） */
  readonly inputRecordId: number | null
  /** 仅输入文件有：归属输入项 ID（自由输入为 null） */
  readonly inputItemId: number | null
}

/** 节点信息的技能项（RequirementNodeVO.skills）。 */
export interface RequirementNodeSkillVO {
  readonly skillId: number
  readonly name: string
  readonly versionNo: number | null
}

/** 节点信息的输入项（RequirementNodeVO.inputs）。 */
export interface RequirementNodeInputVO {
  /** free 节点为 null */
  readonly itemId: number | null
  /** free 节点为 null */
  readonly name: string | null
  readonly fileKind: string
  readonly required: boolean
  readonly files: FileVO[]
}

/** 节点信息的输出项（RequirementNodeVO.outputs）。 */
export interface RequirementNodeOutputVO {
  readonly itemKey: string
  readonly name: string
  readonly fileKind: string
  readonly required: boolean
  readonly submitted: boolean
  readonly versionNo: number | null
  readonly committedAt: string | null
  readonly file: FileVO | null
}

/** 上一节点输出（RequirementNodeVO.previousNode）。 */
export interface PreviousNodeVO {
  readonly seq: number
  readonly name: string
  readonly outputs: { readonly itemKey: string; readonly name: string; readonly file: FileVO | null }[]
}

/** 节点信息页数据（api.md §8 GET）。 */
export interface RequirementNodeVO {
  readonly id: number
  readonly seq: number
  readonly name: string
  readonly description: string | null
  readonly status: 'waiting' | 'current' | 'done' | string
  readonly inputType: 'free' | 'fixed' | string
  readonly skills: RequirementNodeSkillVO[]
  readonly inputs: RequirementNodeInputVO[]
  readonly outputs: RequirementNodeOutputVO[]
  readonly previousNode: PreviousNodeVO | null
  /** 只读：非当前节点、需求已完成、或我不是创建人（且不是 owner） */
  readonly readonly: boolean
  /** 节点默认会话的 DSH 会话标识 */
  readonly defaultSessionId: string | null
}

/** 会话（api.md SessionVO）。 */
export interface SessionVO {
  readonly id: number
  readonly requirementId: number
  readonly dshSessionId: string
  readonly title: string | null
  readonly lastActiveAt: string | null
  readonly archived: boolean
  readonly isNodePrimary: boolean
}

/** 推进预览（api.md §8 advance-preview）。 */
export interface AdvancePreviewVO {
  readonly ready: boolean
  readonly remainingRequiredOutputs: string[]
  readonly remainingRequiredInputs: number[]
  readonly requiresAnyInput: boolean
  readonly missingDescriptions: string[]
}

/** 上传节点输入的结果（api.md §8 POST 上传输入）。 */
export interface NodeInputUploadVO {
  readonly fileId: number
  readonly originalName: string
  readonly sizeBytes: number
  readonly inputRecordId: number
  readonly inputItemId: number | null
  readonly uploadedAt: string | null
}

// ---------------------------------------------------------------------------
// 请求体
// ---------------------------------------------------------------------------

/** 创建 / 编辑项目。 */
export interface ProjectWriteRequest {
  readonly name: string
  readonly description?: string | undefined
}

/** 创建流程。 */
export interface WorkflowCreateRequest {
  readonly name: string
  readonly description?: string | undefined
}

/** 更新流程名称 / 说明（revision 原样回传）。 */
export interface WorkflowUpdateRequest extends WorkflowCreateRequest {
  readonly revision: number
}

/** 整体保存节点列表的节点项（PUT /api/workflows/{id}/nodes）。 */
export interface WorkflowNodeWrite {
  /** 已有节点带原 id；新增节点为 null */
  readonly nodeId: number | null
  readonly name: string
  readonly description?: string | undefined
  readonly inputType: 'free' | 'fixed'
  /** 顺序即加载顺序 */
  readonly skillIds: number[]
  /** 输入项（free 时后端忽略） */
  readonly inputs: WorkflowItemWrite[]
  /** 输出项（至少 1 项） */
  readonly outputs: WorkflowItemWrite[]
}

/** 输入项 / 输出项（整体保存用）。 */
export interface WorkflowItemWrite {
  /** 输出项必填，节点内唯一；输入项忽略 */
  readonly itemKey?: string | undefined | undefined
  readonly name: string
  readonly description?: string | undefined
  readonly fileKind?: string | undefined
  readonly required?: boolean | undefined
}

/** 整体保存节点列表（revision 原样回传）。 */
export interface WorkflowNodesRequest {
  readonly revision: number
  readonly nodes: WorkflowNodeWrite[]
}

/** 创建需求（必须带 workflowId）。 */
export interface RequirementCreateRequest {
  readonly title: string
  readonly description?: string | undefined
  readonly workflowId: number
}

/** 编辑需求（revision 原样回传）。 */
export interface RequirementUpdateRequest {
  readonly title: string
  readonly description?: string | undefined
  readonly revision: number
}

/** 登记新会话。 */
export interface SessionCreateRequest {
  readonly dshSessionId: string
  readonly nodeId?: number | undefined
  readonly title?: string | undefined
}
