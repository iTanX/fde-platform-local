# FDE 交付工作台

把 DeepSeek Harness 作为 AI 员工的作业界面：项目成员配置**流程**（节点 + 技能 + 输入输出项），
创建**需求**后按节点推进，模型在节点的原生 DSH 会话里产出交付物，页面可直接下载与归档。

**自带一份可携带的 dsh（Web 形态）**，不依赖也不读写你本机已安装的 DeepSeek Harness。

---

## 快速开始

### 前置条件

| 项 | 要求 |
|---|---|
| Node.js | ≥ 24.9（`node -v`） |
| 包管理器 | corepack（Node 自带）或 pnpm 11 |
| JDK + Maven | Java 17+ / Maven 3.9+（跑后端） |
| MySQL | 5.7 / 8.0 / 8.4（跑后端） |
| 磁盘 | ≥ 8 GB（首次构建产物约 5 GB） |
| 网络 | 首次构建需访问 npm registry 与 github.com |

### 四步跑起来

```bash
# 1) 建库（先建库与表，再可选导入演示数据）
mysql -h <host> -P 3306 -u <user> -p < docs/database/001_init.sql
mysql -h <host> -P 3306 -u <user> -p < docs/database/002_demo_seed.sql   # 可选

# 2) 构建自带 dsh（首次 5~15 分钟）
./scripts/setup-runtime.sh

# 3) 构建后端
cd workbench && mvn -q -DskipTests package && cd ..

# 4) 启动（数据库连接信息用环境变量给）
FDE_DB_URL='jdbc:mysql://<host>:3306/fde_workbench?sessionVariables=time_zone=%27%2B08%3A00%27&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true' \
FDE_DB_USERNAME=root \
FDE_DB_PASSWORD='<password>' \
./scripts/start-all.sh
```

启动后终端会打印一个**带 `?token=` 的授权地址**，用浏览器打开它（直接访问 `127.0.0.1:3080` 会 401）。

演示账号：`demo`、`demo2`，**密码留空**。

### 配置模型

进入界面 → 设置 → 模型 → 添加模型提供商 → 填入 API 密钥。
凭据保存在项目自己的 `runtime/dsh-home` 内，**不会**写进你的 `~/.dsh`。

### 更省事的配置方式

```bash
cp scripts/local.env.example scripts/local.env   # 填入数据库密码等
./scripts/start-all.sh
```

`scripts/local.env` 已在 `.gitignore` 中，不会被提交。

---

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `FDE_DB_URL` | 内置本地默认值 | JDBC 连接串；换机器**必须**改 |
| `FDE_DB_USERNAME` | `root` | 数据库用户 |
| `FDE_DB_PASSWORD` | 必填 | 数据库密码；不给则拒绝启动 |
| `FDE_SERVER_PORT` | `8081` | Java 后端端口 |
| `FDE_DSH_PORT` | `3080` | dsh web 端口 |
| `FDE_INTERNAL_TOKEN` | 自动生成 | 插件与后端之间的内部令牌，同一进程组内共享 |
| `FDE_STORAGE_ROOT` | `./data` | 节点输入 / 输出文件的存储根 |
| `FDE_WORKBENCH_ALLOWED_ORIGINS` | 按端口自动推导 | 允许跨源访问后端的来源 |
| `FDE_NO_BACKEND` | `0` | 设 `1` 只启动 dsh、不启动后端 |

---

## 项目结构

```
workbench/                Java 后端（Spring Boot 3.2 + MyBatis + MySQL）
  workbench-app/            启动入口与集成测试
  workbench-api/            Controller / DTO / VO
  workbench-service/        业务逻辑
  workbench-dao/            Mapper 与实体
  workbench-common/         通用结果与异常
fde-workbench-bundle/     DSH 插件（Host：模型工具 + 技能；Client：工作台页面）
docs/                     设计文档与验证记录
  PRD.md                    产品需求与验收标准
  architecture.md           总体架构
  database.md               数据模型与存储布局
  api.md                    接口契约
  plugin-integration.md     插件与 DSH 的集成方式
  runtime.md                自带 dsh 的构建、启动、故障排查
  development-plan.md       开发计划
  task-decomposition.md     任务卡与完成状态
scripts/                  构建与启动脚本
```

---

## 常见问题

**端口被占用**：`FDE_DSH_PORT=3081 FDE_SERVER_PORT=8082 ./scripts/start-all.sh`

**`/api/health` 返回 `db=down`**：检查 `FDE_DB_URL`、`FDE_DB_USERNAME`、`FDE_DB_PASSWORD`，
以及 MySQL 是否允许目标机器连接。

**页面提示无法连接后端**：通常是 `FDE_DB_URL` 指错了主机，或后端没起来。
先 `curl http://127.0.0.1:8081/api/health` 确认返回 `db=ok`。

**Windows**：用 Git Bash 或 WSL 执行脚本。

完整的前置条件、构建步骤、故障排查见 [docs/runtime.md](docs/runtime.md)。

---

## 数据库版本

建库脚本同时兼容 **MySQL 5.7 / 8.0 / 8.4**（已在 5.7.44 与 8.4.8 上完整验证建库、种子数据与全部集成测试）。

如需指定版本以外的行为差异，见 [docs/database.md](docs/database.md)。
