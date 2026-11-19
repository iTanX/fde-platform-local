---
document: api
status: draft
updated: "2026-11-19"
base_url: "http://127.0.0.1:8081"
---

# Web 工作台 接口设计

## 1. 通用约定

### 1.1 请求

| 项 | 约定 |
|---|---|
| 基础路径 | `/api` |
| 内容类型 | `application/json; charset=utf-8`；文件上传 `multipart/form-data` |
| 鉴权 | `Authorization: Bearer <JWT>`；`/api/auth/**` 与 `/api/public/**` 免鉴权 |
| 分页参数 | `page`（从 1 开始）、`size`（默认 20，最大 100） |
| 排序参数 | `sort`（字段）与 `order`（`asc`/`desc`），默认 `createdAt desc` |
| 乐观锁版本号 | **PUT/整体保存**放请求体 `revision`；**DELETE** 放查询参数 `?revision=N`（部分客户端会丢弃 DELETE 请求体）。缺失或已过期均返回 `10402`/`10001` |

### 1.2 统一响应

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

- `code = 0` 表示成功；非 0 见 [1.3 错误码](#13-错误码)。
- 分页数据：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "items": [], "total": 0, "page": 1, "size": 20 }
}
```

- HTTP 状态码与 `code` 同时表达：HTTP 用于网络层判定，`code` 提供业务细分。

### 1.3 错误码

| HTTP | code | 含义 |
|---|---|---|
| 400 | 10001 | 参数校验失败 |
| 401 | 10101 | 未登录或 token 过期 |
| 401 | 10102 | 用户名或密码错误 |
| 403 | 10201 | 不是项目成员 |
| 403 | 10202 | 无操作权限（非 owner / 非数据创建者） |
| 404 | 10301 | 资源不存在 |
| 409 | 10401 | 名称重复 |
| 409 | 10402 | 乐观锁冲突（revision 不一致） |
| 409 | 10403 | 被引用，不能删除 |
| 409 | 10404 | 状态不允许该操作（如非当前节点上传） |
| 413 | 10501 | 文件超出大小限制 |
| 415 | 10502 | 文件类型不允许 |
| 500 | 19901 | 服务内部错误 |
| 502 | 19902 | 下游不可用（DSH 或存储） |

### 1.4 主要 DTO

```jsonc
// UserVO
{ "id": 1, "username": "demo", "displayName": "演示 Owner" }

// ProjectVO
{ "id": 1, "name": "示例项目", "description": "…", "ownerId": 1,
  "memberCount": 3, "requirementCount": 5, "revision": 4, "createdAt": "2026-11-19T10:00:00+08:00" }

// MemberVO
{ "userId": 2, "username": "demo2", "displayName": "演示成员", "role": "member", "joinedAt": "…" }

// SkillVO
{ "id": 1, "name": "需求拆解", "description": "…", "currentVersionId": 3, "currentVersionNo": 2,
  "usageCount": 1, "createdBy": 1, "createdAt": "…" }

// WorkflowVO
{ "id": 1, "name": "需求分析标准流程", "description": "…", "nodeCount": 3, "revision": 2, "nodes": [NodeVO] }

// NodeVO
{ "id": 11, "seq": 1, "name": "需求澄清", "description": "…", "inputType": "free",
  "skills": [{ "skillId": 1, "name": "需求拆解", "seq": 1 }],
  "inputs": [{ "itemId": 1, "seq": 1, "name": "客户原话整理", "description": "…", "fileKind": "text", "required": true }],
  "outputs": [{ "itemId": 1, "seq": 1, "itemKey": "requirement-items", "name": "需求条目清单", "description": "…", "fileKind": "text", "required": true }] }

// RequirementVO
{ "id": 1, "projectId": 1, "workflowId": 1, "workflowName": "…", "title": "…", "description": "…",
  "creatorId": 1, "status": "in_progress", "currentSeq": 2, "nodeCount": 3, "revision": 1,
  "editable": true, "createdAt": "…", "updatedAt": "…" }

// RequirementNodeVO（节点信息页）
{ "id": 101, "seq": 2, "name": "需求拆解", "description": "…", "status": "current",
  "inputType": "fixed",
  "skills": [{ "skillId": 1, "name": "需求拆解", "versionNo": 2 }],
  "inputs": [{ "itemId": 1, "name": "客户原话整理", "fileKind": "text", "required": true,
               "files": [{ "fileId": 9, "originalName": "客户原话.md", "sizeBytes": 1024, "uploadedAt": "…" }] }],
  "outputs": [{ "itemKey": "requirement-items", "name": "需求条目清单", "fileKind": "text", "required": true,
                "submitted": true, "versionNo": 1, "committedAt": "…",
                "file": { "fileId": 12, "originalName": "requirement-items.md", "sizeBytes": 2048 } }],
  "previousNode": { "seq": 1, "name": "需求澄清",
                    "outputs": [{ "itemKey": "clarify-notes", "name": "澄清记录",
                                  "file": { "fileId": 7, "originalName": "clarify-notes.md", "sizeBytes": 800 } }] },
  "readonly": false,
  "defaultSessionId": "sess_abc" }

// SessionVO
{ "id": 1, "requirementId": 1, "dshSessionId": "sess_abc", "title": "需求拆解对话",
  "lastActiveAt": "…", "archived": false, "isNodePrimary": true }
```

### 1.5 时间与时区

- 所有时间字段由应用写入（数据库列无默认值），统一为 `+08:00`。
- JDBC 会话时区固定 `+08:00`（URL 参数 `sessionVariables=time_zone='%2B08:00'`），服务启动时断言 `SELECT @@session.time_zone`，并在 `/api/health` 返回 `sessionTimeZone`。
- VO 时间字段统一使用 `TimeFormats` 序列化，保证 JSON 中带时区偏移。

### 1.6 权限记号

| 记号 | 含义 |
|---|---|
| `M` | 项目成员即可 |
| `C` | 数据创建者，或项目 owner |
| `O` | 仅项目 owner |
| `-` | 免鉴权 |

---

## 2. 认证 `/api/auth`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/register` | - | 注册 |
| POST | `/api/auth/login` | - | 登录 |
| GET | `/api/auth/me` | 已登录 | 当前用户 |

### POST `/api/auth/register`

```json
{ "username": "demo", "password": "123456", "displayName": "演示" }
```

- `username` 必填，3–32 字符，仅字母数字下划线；`password` **可为空字符串**；`displayName` 可空，默认等于 `username`。
- 成功：`{ "code": 0, "data": { "userId": 1 } }`
- 失败：用户名已存在 → `10401`。

### POST `/api/auth/login`

```json
{ "username": "demo", "password": "123456" }
```

- 成功：

```json
{ "code": 0, "data": { "token": "<JWT>", "expiresAt": "2026-11-20T10:00:00+08:00", "user": { "id": 1, "username": "demo", "displayName": "演示" } } }
```

- 用户不存在 → `10102`，`message` 为"用户不存在，请先注册"。
- 密码错误 → `10102`。

---

## 3. 项目 `/api/projects`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/projects` | 已登录 | 我所属的项目（分页，支持 `keyword`） |
| POST | `/api/projects` | 已登录 | 创建项目（创建人为 owner） |
| GET | `/api/projects/{id}` | M | 项目详情 |
| PUT | `/api/projects/{id}` | O | 编辑（带 `revision`） |
| DELETE | `/api/projects/{id}?revision=N` | O | 删除（逻辑删除；`revision` 必填，二次确认由前端负责） |

### POST `/api/projects`

```json
{ "name": "示例项目", "description": "…" }
```

- 成功返回 `ProjectVO`；`name` 必填 1–128 字符。

### GET `/api/projects`

- 查询条件：`keyword`（模糊匹配 `name`）。
- 只返回 `deleted=0` 且我在成员表中的项目。

---

## 4. 成员 `/api/projects/{projectId}/members`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/projects/{projectId}/members` | M | 成员列表 |
| POST | `/api/projects/{projectId}/members` | O | 添加成员 `{ "username": "demo2" }` |
| DELETE | `/api/projects/{projectId}/members/{userId}` | O | 移除成员（不能移除 owner 自己，需先转让）；需带 `?revision=` 的项目版本号；同时删除该成员在此项目内的技能（含各版本），若其仍有需求则整体回滚 |
| POST | `/api/projects/{projectId}/members/{userId}/transfer-owner` | O | 转让 owner |

- 转让成功后：目标用户 `role=owner`，原 owner `role=member`，`wb_project.owner_id` 更新；**必须在同一事务内同时改这两处**。
- **owner 判定为双源校验**：`wb_project.owner_id == 当前用户` **且** 成员表中存在该用户的 `role=owner` 记录。任一处不符按非 owner 处理（`10202`）。这样即便转让只改了一半也会被立刻暴露，而不会出现两个 owner 或无人可管。
- 移除成员的事务顺序：① 删除 `wb_skill`（`created_by=该用户`）及其版本；② 删除成员记录。任一步失败整体回滚。
- 添加不存在的用户名 → `404 10301`；已是成员 → `409 10401`。

---

## 5. 技能 `/api/projects/{projectId}/skills`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/projects/{projectId}/skills` | M | 技能列表（含版本与引用数） |
| POST | `/api/projects/{projectId}/skills` | M | 上传/升级技能（multipart） |
| GET | `/api/skills/{skillId}/versions` | M | 版本列表 |
| GET | `/api/skills/{skillId}/versions/{versionNo}/download` | M | 下载该版本（zip） |
| GET | `/api/skills/{skillId}/files?path=SKILL.md` | M | 预览单个文件 |
| DELETE | `/api/skills/{skillId}` | C（owner 或上传者） | 删除技能 |

### POST 上传

- `multipart/form-data`：`file`（zip，内含 `SKILL.md` 或 `skills/<name>/SKILL.md`）。
- 后端解包、校验入口文件存在、计算 `files_json` 与 `checksum`。
- 同名技能：新建 `wb_skill_version`（`version_no+1`）并把 `wb_skill.current_version_id` 指向新版本。
- 失败：无 `SKILL.md` → `400 10001`；超过限制 → `413 10501`。

### DELETE 删除

- 校验引用：`wb_workflow_node_skill` 中仍被**未完成需求**的节点实例引用（按 `skills_snapshot` 匹配且节点实例所属需求 `status != completed`）。
- 被引用 → `409 10403`，`data` 返回引用清单：

```json
{ "references": [{ "requirementId": 3, "requirementTitle": "…", "nodeName": "需求拆解" }] }
```

---

## 6. 流程 `/api/projects/{projectId}/workflows`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/projects/{projectId}/workflows` | M | 流程列表 |
| POST | `/api/projects/{projectId}/workflows` | M | 创建流程 |
| GET | `/api/workflows/{id}` | M | 流程详情（含节点、输入项、输出项、技能） |
| PUT | `/api/workflows/{id}` | C | 更新流程名称/说明 |
| DELETE | `/api/workflows/{id}` | C | 删除流程（被需求引用时 `409 10403`） |
| PUT | `/api/workflows/{id}/nodes` | C | **整体保存节点列表**（顺序、内容、输入输出、技能） |

### PUT `/api/workflows/{id}/nodes`

一次性提交整个节点列表，后端按新顺序重排 `seq`，并对比删除/新增的节点。已有需求使用快照，不受影响。

```json
{
  "revision": 2,
  "nodes": [
    {
      "nodeId": 11,
      "name": "需求澄清",
      "description": "…",
      "inputType": "free",
      "skillIds": [1, 4],
      "inputs": [],
      "outputs": [{ "itemId": 1, "itemKey": "clarify-notes", "name": "澄清记录", "fileKind": "text", "required": true }]
    },
    {
      "nodeId": null,
      "name": "需求拆解",
      "inputType": "fixed",
      "skillIds": [1],
      "inputs": [{ "name": "客户原话整理", "fileKind": "text", "required": true }],
      "outputs": [
        { "itemKey": "requirement-items", "name": "需求条目清单", "fileKind": "text", "required": true },
        { "itemKey": "acceptance-criteria", "name": "验收标准", "fileKind": "text", "required": true }
      ]
    }
  ]
}
```

校验规则：

1. 至少 1 个节点；节点 `name` 非空且长度 ≤ 128。
2. `outputs` 至少 1 项；`itemKey` 在本节点内唯一，匹配 `^[a-z0-9][a-z0-9-]{0,63}$`。
3. `inputType=fixed` 时 `inputs` 至少 1 项；`free` 时忽略 `inputs`。
4. `skillIds` 中的技能必须属于本项目。
5. 数组顺序即执行顺序。

---

## 7. 需求 `/api/projects/{projectId}/requirements`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/projects/{projectId}/requirements` | M | 需求列表（支持 `keyword`、`status`） |
| POST | `/api/projects/{projectId}/requirements` | M | 创建需求（必须带 `workflowId`） |
| GET | `/api/requirements/{id}` | M | 需求详情（含节点列表与状态） |
| PUT | `/api/requirements/{id}` | C | 编辑标题/描述（带 `revision`） |
| DELETE | `/api/requirements/{id}` | C | 删除需求 |

### POST 创建

```json
{ "title": "客户工单分诊需求", "description": "…", "workflowId": 1 }
```

- 生成节点实例（快照输入/输出/技能版本），第一节点 `current`。
- 响应 `RequirementVO`。

### GET `/api/requirements/{id}`

```json
{ "code": 0, "data": {
  "requirement": { },
  "nodes": [{ "id": 101, "seq": 1, "name": "需求澄清", "status": "done", "completedAt": "…" }],
  "permissions": { "canOperate": true, "isCreator": true }
} }
```

- `canOperate`：当前登录人为需求创建人或项目 owner，且需求 `status=in_progress`。

---

## 8. 节点信息 `/api/requirements/{requirementId}/nodes`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/requirements/{requirementId}/nodes/{nodeId}` | M | 节点信息页数据 |
| POST | `/api/requirements/{requirementId}/nodes/{nodeId}/inputs` | C | 上传节点输入（multipart） |
| DELETE | `/api/requirements/{requirementId}/nodes/{nodeId}/inputs/{inputRecordId}` | C | 删除已上传输入 |
| GET | `/api/requirements/{requirementId}/nodes/{nodeId}/advance-preview` | C | 查看推进条件是否满足 |

### GET 节点信息

返回 `RequirementNodeVO`（见 1.4）。规则：

- `readonly = true` 的三种情况：非当前节点、非需求创建人（且非 owner）、需求已完成。
- `readonly = true` 时前端不显示会话页签与上传入口。
- `previousNode` 为 `seq-1` 节点的输出文件；第一个节点为 `null`。
- `defaultSessionId` 来自会话解析（见 9.1）。

### POST 上传输入

- `multipart/form-data`：`file`、`inputItemId`（固定输入时必填）。
- 校验：需求创建人或 owner；节点为当前节点且需求进行中；`inputItemId` 属于该节点快照；文件类型符合 `file_kind`；大小 ≤ 限制。
- 成功返回 `{ "fileId": 9, "originalName": "客户原话.md", "sizeBytes": 1024 }`。

---

## 9. 会话 `/api/requirements/{requirementId}/sessions`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/requirements/{requirementId}/sessions` | M | 需求下全部会话（按 `lastActiveAt` 倒序） |
| POST | `/api/requirements/{requirementId}/sessions` | C | 登记新建会话并绑定节点（修正 M4） |
| POST | `/api/sessions/{sessionId}/touch` | C | 更新最后活跃时间 |

### POST 新建会话登记

```json
{ "dshSessionId": "sess_xyz", "nodeId": 101, "title": "需求拆解会话" }
```

- 事务：插入 `wb_agent_session`（`requirement_id`、`dsh_session_id` 唯一）→ 插入 `wb_session_runtime_info`（`last_node_instance_id`）→ 若该节点 `primary_session_id` 为空则设为新会话 → 插入 `wb_node_session_link`。
- `dshSessionId` 已存在 → `409 10401`。

### 节点默认会话解析顺序

1. `wb_requirement_node.primary_session_id`。
2. 需求下 `archived=0` 中 `last_active_at` 最大者。
3. 都没有 → 返回 `{ "sessionId": null, "needCreate": true }`，由插件调用 `uiWorkspace.connectWorkspace` 创建后再调本组 POST 登记。

---

## 10. 文件 `/api/files`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/files/{fileId}` | M | 文件元信息 |
| GET | `/api/files/{fileId}/download` | M | 下载（`attachment`） |
| GET | `/api/files/{fileId}/preview` | M | 文本/图片预览（Markdown、txt、png/jpg） |

- 校验：登录用户是文件所属项目的成员。
- `Content-Disposition` 使用 `filename*=UTF-8''…` 支持中文名。

---

## 11. 内部接口 `/internal`（仅插件调用）

> 由独立过滤器校验 `X-Internal-Token`，且只在 `127.0.0.1` 绑定下生效；普通用户 token 无效。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/nodes/{nodeInstanceId}/outputs/{itemKey}/commit` | 登记模型产出并推进节点 |
| GET | `/internal/nodes/{nodeInstanceId}/context` | 供 Host 工具 `node_context_get` 取节点快照 |
| POST | `/internal/sessions` | 插件新建会话后登记（同 9 的 POST，但免 JWT） |
| POST | `/internal/nodes/{nodeInstanceId}/reference` | 记录模型读取了某引用文件（用于展示"模型读了什么"） |

### POST commit

```json
{ "relativePath": ".workbench/101/requirement-items.md", "sha256": "…", "operator": "agent" }
```

响应：

```json
{ "code": 0, "data": {
  "committed": true, "versionNo": 1,
  "advanced": false, "remainingRequiredOutputs": ["acceptance-criteria"] } }
```

- 后端校验 `relativePath` 必须位于该节点输出目录内，且文件 `sha256` 一致。

---

## 12. 接口与需求追踪

| 接口组 | 需求 |
|---|---|
| `/api/auth/**` | REQ-H1 |
| `/api/projects/**` | REQ-B1~B3、REQ-H2、REQ-H3 |
| `/api/projects/{id}/members/**` | REQ-C1~C4 |
| `/api/projects/{id}/skills/**` | REQ-D1、REQ-D2 |
| `/api/projects/{id}/workflows/**`、`/api/workflows/**` | REQ-E1~E5 |
| `/api/projects/{id}/requirements/**`、`/api/requirements/**` | REQ-F1~F3、REQ-H4 |
| `/api/requirements/{id}/nodes/**` | REQ-F4~F6、REQ-H5 |
| `/api/requirements/{id}/sessions/**` | REQ-F7、REQ-X1、REQ-X2 |
| `/internal/**` | REQ-F6、REQ-G1 |
| `/api/fde-workbench/**`（插件 Host） | REQ-A3、REQ-F7 |
