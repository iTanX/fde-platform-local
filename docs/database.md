---
document: database
status: draft
updated: "2026-11-19"
scripts:
  - docs/database/001_init.sql
  - docs/database/002_demo_seed.sql
---

# Web 工作台 数据库设计

## 1. 数据库基本信息

| 项 | 值 |
|---|---|
| 数据库类型 | MySQL 5.7.44（目标实例实测版本）；脚本同时兼容 8.0 |
| 库名 | `fde_workbench` |
| 字符集 / 排序规则 | `utf8mb4` / `utf8mb4_unicode_ci`（5.7 无 `0900` 系列） |
| 存储引擎 | InnoDB |
| 表名前缀 | `wb_`（Web Bench） |
| 建库脚本 | [001_init.sql](database/001_init.sql) |
| 演示数据脚本 | [002_demo_seed.sql](database/002_demo_seed.sql)（仅测试库执行） |
| 变更脚本规范 | 见 [database/README.md](database/README.md) |
| 会话时区 | 统一 `+08:00`；脚本内 `SET time_zone = '+08:00'`，JDBC 需配置 `sessionVariables=time_zone='%2B08:00'`，多实例一致 |

### 1.1 执行方式

```bash
# 建库 + 建表（可重复执行，不删除数据）
# 注意：目标实例 MySQL 5.7.44 的 root 密码需提供；本仓库不保存密码，按环境变量或交互输入
mysql -h 192.168.2.125 -P 3306 -u root -p --default-character-set=utf8mb4 < docs/database/001_init.sql

# 可选：写入演示项目与演示流程
mysql -h 192.168.2.125 -P 3306 -u root -p < docs/database/002_demo_seed.sql

# 校验
mysql -h 192.168.2.125 -P 3306 -u root -p -e "USE fde_workbench; SHOW TABLES;"
```

> 本轮执行结果见 [实施记录](#8-本轮实施记录)。

## 2. 设计原则

1. **项目是数据顶层维度**：除 `wb_user`、`wb_builtin_skill` 外，所有业务表都能沿 `project_id` 或外键链回到项目，权限拦截器据此判定。
2. **需求创建时快照流程**：`wb_requirement_node` 保存节点名称、输入项、输出项、技能及其版本号。流程或技能后续修改不影响已有需求，这是"未完成实例引用技能时不能删除技能"的判定依据之一。
3. **会话归属需求，不归属节点**：一个需求可有多个会话（`wb_agent_session`）；节点推进后默认沿用同一会话，也可新建会话（修正 M2）。节点与会话只有展示用的弱关联（`wb_node_session_link`）。
4. **文件内容与登记分离**：文件本体存于 `fde.workbench.storage-root` 下的路径，数据库中保存相对路径与 `sha256`，便于校验、迁移与版本比对。
5. **可编辑聚合根使用乐观锁**：`wb_project.revision`、`wb_requirement.revision` 与前端表单版本一起提交，冲突返回 `40901`。
6. **删除语义显式区分**：
   - 逻辑删除（`deleted=1`）：项目、技能、流程。删除前必须通过引用校验。
   - 物理删除并级联：流程节点、输入项、输出项、成员、节点实例（随需求删除）。
   - 状态字段：需求 `status`、节点 `status`、会话 `archived`。

## 3. 表清单

| # | 表名 | 中文名 | 所属 Feature | 说明 |
|---|---|---|---|---|
| 1 | `wb_user` | 用户 | H 用户 | 注册/登录主体 |
| 2 | `wb_project` | 项目 | B 项目中心 | 数据顶层维度 |
| 3 | `wb_project_member` | 项目成员 | C 成员 | owner / member 角色 |
| 4 | `wb_skill` | 技能 | D 技能 | 项目内技能定义 |
| 5 | `wb_skill_version` | 技能版本 | D 技能 | 保留历史版本，可下载 |
| 6 | `wb_workflow` | 流程 | E 流程 | 需求绑定的模板 |
| 7 | `wb_workflow_node` | 流程节点 | E 流程 | 按 `seq` 自上而下执行 |
| 8 | `wb_workflow_node_skill` | 节点技能 | E 流程 | **多技能**关联（修正 M3） |
| 9 | `wb_node_input_item` | 固定输入项 | E 流程 | `input_type=fixed` 时存在 |
| 10 | `wb_node_output_item` | 固定输出项 | E 流程 | 必填项未提交时节点不推进 |
| 11 | `wb_requirement` | 需求 | F 需求 | 绑定一个流程 |
| 12 | `wb_requirement_node` | 需求节点实例 | F 需求 | 流程快照 + 推进状态 |
| 13 | `wb_file` | 文件登记 | 通用 | 原件与产出的统一登记 |
| 14 | `wb_requirement_node_input` | 节点输入文件 | F 需求 | 自由输入或固定输入项的上传结果 |
| 15 | `wb_requirement_node_output` | 节点输出 | F 需求 | 一个输出项只有一条当前版本 |
| 16 | `wb_agent_session` | 需求会话 | F 需求 | **需求 → 多会话**（修正 M1） |
| 17 | `wb_session_runtime_info` | 会话运行信息 | F 需求 | 恢复定位、最后节点、预设 |
| 18 | `wb_node_session_link` | 节点会话关联 | F 需求 | 展示用弱关联（多对多，保留历史） |
| 19 | `wb_builtin_skill` | 内置技能登记 | G 内置技能 | 随插件发布的指导技能 |

## 4. 实体关系

```text
wb_user 1──n wb_project_member n──1 wb_project
wb_user 1──n wb_project (owner)
wb_project 1──n wb_skill 1──n wb_skill_version
wb_project 1──n wb_workflow 1──n wb_workflow_node
     wb_workflow_node n──n wb_skill            (wb_workflow_node_skill)
     wb_workflow_node 1──n wb_node_input_item
     wb_workflow_node 1──n wb_node_output_item
wb_project 1──n wb_requirement n──1 wb_workflow
wb_requirement 1──n wb_requirement_node          (创建时快照流程节点)
wb_requirement 1──n wb_agent_session              (一个需求多个会话)
     wb_agent_session 1──1 wb_session_runtime_info
     wb_requirement_node n──n wb_agent_session     (wb_node_session_link)
wb_requirement_node 1──n wb_requirement_node_input n──1 wb_file
wb_requirement_node 1──n wb_requirement_node_output n──1 wb_file
```

## 5. 关键字段与约束

### 5.1 多技能（修正 M3）

- 一个流程节点通过 `wb_workflow_node_skill` 关联**多个**技能，`seq` 决定提示与加载顺序。
- `wb_requirement_node.skills_snapshot` 形如：

```json
[
  { "skillId": 1, "name": "需求拆解", "versionId": 3, "versionNo": 2 },
  { "skillId": 4, "name": "风险识别", "versionId": 7, "versionNo": 1 }
]
```

- 判定"技能被未完成实例引用"时，同时检查 `wb_workflow_node_skill`（流程模板引用）与 `skills_snapshot`（实例快照引用）。

### 5.2 多会话与会话恢复（修正 M1、M2）

- `wb_agent_session` 以 `dsh_session_id` 唯一，记录所属 `requirement_id`。
- 需求级默认会话：`requirement_id` 相同、`archived=0`、按 `last_active_at DESC` 取第一条；没有则新建。
- **新建会话绑定节点（修正 M4）**：在需求页任何人新建的会话，都必须绑定到新建时所在的节点实例，写入 `wb_requirement_node.primary_session_id`（该节点已存在绑定则不覆盖）以及 `wb_node_session_link`（多对多，保留全部历史）。
- **节点默认会话**：进入节点时优先打开该节点的 `primary_session_id`；为空时回退到需求级默认会话；两者都没有才新建。因此"节点推进后默认沿用同一会话、也可以新建会话"由数据明确表达，不依赖页面缓存。
- `wb_session_runtime_info.last_node_instance_id` 记录"该会话最后在哪个节点"，用于进入需求时把页面恢复到上次的位置。
- 若 DSH 侧会话已不存在（会话日志被清理），后端把该记录标记 `archived=1` 并在页面提示"会话已失效，请新建会话"，**不伪造历史**。

### 5.3 节点推进（`wb_requirement_node`）

推进条件（由后端在一个事务内判定并写入）：

1. 所有 `required=1` 的输出项在 `wb_requirement_node_output` 中都有记录。
2. 所有 `required=1` 的固定输入项在 `wb_requirement_node_input` 中都有记录；`input_type=free` 时至少要有一个 `input_item_id IS NULL` 的输入文件。

满足后：当前节点置 `done`（写 `completed_at`、`completed_by`），`seq+1` 的节点置 `current`（写 `started_at`），`wb_requirement.current_seq+1`；已是最后节点时 `wb_requirement.status='completed'`。

### 5.4 输出项版本

`wb_requirement_node_output` 对 `(node_instance_id, item_key)` 唯一，因此一个输出项只有一个"当前文件"。重新提交时：

- `version_no + 1`；
- 旧 `wb_file` 记录保留（`ref_type='node_output'`），便于审计；
- 页面默认只展示当前文件并允许下载。

### 5.5 文件存储路径约定

```text
<fde.workbench.storage-root>/
├── projects/<projectId>/
│   ├── skills/<skillId>/v<versionNo>/<原始相对路径>     # 技能包解包后的文件
│   └── requirements/<requirementId>/
│       ├── inputs/<nodeSeq>/<fileId>_<原始文件名>       # 节点输入
│       ├── outputs/<nodeSeq>/<itemKey>.<ext>            # 节点输出（模型写入）
│       └── docs/                                        # 需求说明等附属资料
└── ws/<requirementId>/                                  # 该需求的 DSH workspace 根
```

- DSH workspace 根 `<storage-root>/ws/<requirementId>` 通过软链或直接目录指向 `requirements/<requirementId>`，模型以相对路径读写，跨会话、跨节点自然持久。
- 模型固定写入目录：`.workbench/<nodeInstanceId>/<itemKey>.<ext>`，`node_output_write` 工具拒绝写到该目录之外。

## 6. 索引与外键说明

- 项目列表查询走 `idx_project_owner` + `idx_project_deleted`；成员判权走 `uk_member_project_user`。
- 需求列表走 `idx_requirement_project(project_id, status)`。
- 节点渲染一次读取整条流程：`wb_requirement_node(requirement_id, seq)`。
- 会话列表走 `idx_agent_session_req(requirement_id, archived, last_active_at)`。
- 全部外键显式命名 `fk_<表名简化>_<被引用对象>`，便于定位约束错误。

## 7. 待确认项

| # | 待确认 | 当前处理 |
|---|---|---|
| D1 | 技能包是否需要保存原始 zip 以便"完整下载" | 当前只解包保存文件，下载时后端按 `files_json` 重新打包 |
| D2 | 是否保留输出项的删除/回滚历史 | 当前保留旧 `wb_file` 记录但不展示；如需历史列表再加接口 |
| D3 | 多租户是否需要 | 当前无租户概念；如需，需在全部表增加 `tenant_id` 并改造索引 |

## 8. 本轮实施记录

真实执行结果（2026-11-19，Mac 上通过 Homebrew 安装的 `mysql-client 26.10.0` 连接）：

| 步骤 | 命令/动作 | 结果 |
|---|---|---|
| 服务端版本 | `SELECT VERSION()` | **5.7.44**（非 8.0，脚本已按 5.7 改写） |
| 库存在性 | `SELECT SCHEMA_NAME … WHERE SCHEMA_NAME='fde_workbench'` | 空结果，确认不存在，可安全新建 |
| 执行建库 | `mysql … < docs/database/001_init.sql` | 退出码 0，无错误 |
| 表数量 | `information_schema.tables` | **19** |
| 外键数量 | `information_schema.table_constraints`（FOREIGN KEY） | **35** |
| 内置技能 | `SELECT * FROM wb_builtin_skill` | 1 行（`workbench-node-delivery`） |
| 幂等性 | 重复执行 `001_init.sql` | 结构与数据不变（19 表 / 35 外键） |
| 演示数据 | 连续执行 `002_demo_seed.sql` 两次 | 退出码 0，数据不重复（固定主键 + `INSERT IGNORE`） |
| 演示数据校验 | 查询用户/项目/成员/节点/输出项/技能 | demo(owner)、demo2(member)、3 个节点、3 个输出项、1 个技能 |

**MySQL 5.7 适配说明**（相对初版 8.0 脚本的改动）：

1. 排序规则由 `utf8mb4_0900_ai_ci` 改为 `utf8mb4_unicode_ci`。
2. 去除 `DATETIME(3)` 上的 `CURRENT_TIMESTAMP(3)` / `ON UPDATE` 默认值（5.7 不允许），时间列统一由应用写入。
3. 外键改为**建表后集中添加**（`wb_add_fk` 存储过程按 `information_schema` 判断后执行 `ALTER`），便于分步排查并保证可重复执行。
4. 不去使用 `CHECK` 约束、窗口函数、`VALUES` 表构造等 5.7 不支持的语法。
5. 会话时区固定 `+08:00`；JDBC 需配置 `sessionVariables=time_zone='%2B08:00'`。

**未验证项**：

- 演示数据中的 `password_hash` 为 `NULL`（表示注册时未设置密码），登录逻辑由后端实现后验证空密码登录。
- 真实业务读写（乐观锁、事务推进、外键 RESTRICT 的清理顺序）需在阶段 1 起用后端实测。
