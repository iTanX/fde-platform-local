# 2026-11-19 端到端走查记录

> 环境：macOS arm64、Node v24.21.0、`dsh-v0.2.2-rc.5`、Java 17，
> **临时隔离 MySQL 8.4.8**（`/tmp` 下单实例、仅监听 127.0.0.1、用完即删），
> 不连共享演示库、不碰用户本机 DSH profile。
> 浏览器：无头 Chrome，通过 CDP 驱动真实鼠标事件；**未使用任何反代或垫片**。

## 1. 启动

```bash
FDE_DB_PASSWORD=*** FDE_DB_URL=<临时库> FDE_STORAGE_ROOT=<临时目录> \
FDE_INTERNAL_TOKEN=*** ./scripts/start-all.sh
```

- `dsh web` 起在 `127.0.0.1:3080`，Java 后端 `127.0.0.1:8081`，`/api/health` → `db=ok`。
- 启动脚本打印带 `?token=` 的授权地址。

## 2. 跨源（CORS）

| 检查 | 结果 |
|---|---|
| `OPTIONS /api/auth/login`（Origin = 3080，ACRM = POST） | **200**，返回 `Access-Control-Allow-Origin/Methods/Headers` |
| 浏览器内 `fetch` 直连 8081 登录 | **200，type=cors** |
| 下载响应头 | `Access-Control-Expose-Headers: Content-Disposition, Retry-After` |

修复前该预检返回 **403 `Invalid CORS request`**，浏览器里所有 `/api` 请求都是 `Failed to fetch`。

## 3. 走查结果

| 步骤 | 结果 | 证据 |
|---|---|---|
| 侧栏入口「Web 工作台」 | ✅ | 位于全局面板列表首位；大视口下可见可点 |
| 打开工作台面板 | ✅ | `[data-fde-workbench-panel]` 渲染，仍停留在同一页面 |
| 登录（demo，空密码） | ✅ | 进入项目中心，显示「演示 Owner @demo」与演示项目卡片 |
| 项目四 Tab | ✅ | 需求 / 流程 / 技能 / 成员，切换不离开页面 |
| 新建需求 | ✅ | 对话框含标题、说明、流程下拉（「需求分析标准流程（3 个节点）」） |
| 需求详情与节点时间线 | ✅ | 三节点，当前节点高亮「当前节点」，其余「未开始」 |
| 节点页两个视图 | ✅ | 「会话」「节点信息」；切换后 `location.href` 未变 |
| 新建会话 | ✅ | 自动创建需求级工作区并登记，会话显示「默认 · 最近活跃」 |
| 原生会话嵌入 | ✅ | `[data-fde-workbench-embed]` 内有 DSH 原生输入框与模型选择器 |
| 模型产出登记（模拟 commit） | ✅ | 内部接口提交 v1，`committed=true` |
| 先产出后补资料自动推进 | ✅ | 上传输入后响应 `advanced=true, nextNodeName=需求拆解` |
| 节点推进后状态 | ✅ | 节点 1「已完成」，节点 2「当前节点」，需求 `currentSeq=2` |
| 已完成节点只读 | ✅ | 无「会话」页签、0 个上传按钮、显示只读提示 |
| 输出与输入下载 | ✅ | 下载返回 200，内容与写入一致，`filename*` 支持中文 |

## 4. 本次走查发现并修复的问题

| 问题 | 影响 | 修复 |
|---|---|---|
| 前端默认基址缺少 `/api` 前缀 | 所有接口请求打到 `/{path}`，后端返回 403，页面显示「无法连接后端」 | 缺省值改为 `http://127.0.0.1:8081/api` |

## 5. 未验证

| 项 | 原因 |
|---|---|
| 真实模型对话与自动调用工具 | 本机无模型 API Key；产出登记链路已用内部接口等价验证 |
| 同一需求内多会话切换与刷新后恢复 | 已实现并有点对点用例，未在浏览器中完整点验 |
| 磁盘不足、超大文件、并发提交 | 未构造对应场景 |
