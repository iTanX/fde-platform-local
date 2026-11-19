# 数据库脚本说明

## 脚本清单

| 脚本 | 用途 | 可重复执行 | 破坏性 |
|---|---|---|---|
| `001_init.sql` | 建库 `fde_workbench` + 19 张业务表 + 索引 + 外键 + 内置技能登记（MySQL 5.7 语法） | 是（`IF NOT EXISTS` + 外键判存在） | 否 |
| `002_demo_seed.sql` | 演示用户、演示项目、成员、技能、三节点流程与输出项 | 是（固定主键 + `INSERT IGNORE`） | 否，但会写入测试数据 |

## 执行约定

1. **只增不改**：已发布的脚本不得修改内容。结构或数据变化一律新增 `NNN_<变更说明>.sql`，编号连续递增。
2. **每个变更脚本必须写清**：变更原因、涉及表、回滚方式（回滚语句写在脚本末尾的注释块中，默认不执行）。
3. **变更脚本自带前置校验**：例如 `ALTER TABLE` 前用 `information_schema` 查询判断列是否已存在，避免重复执行报错。
4. **命名**：`NNN_动词_对象.sql`，例如 `003_add_requirement_priority.sql`。
5. **生产库执行前**：先在测试库完整跑一遍 `001_init.sql` + 全部变更脚本，确认新库与存量库结构一致。

## 新库部署顺序

```bash
mysql -h <host> -P 3306 -u <user> -p < docs/database/001_init.sql
# 按编号依次执行全部变更脚本
for f in docs/database/0[0-9][0-9]_*.sql; do
  case "$f" in *seed*) continue;; esac
  mysql -h <host> -P 3306 -u <user> -p < "$f"
done
```

## 校验

```sql
USE fde_workbench;
SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema = 'fde_workbench';   -- 期望 19
SELECT COUNT(*) FROM information_schema.table_constraints
 WHERE constraint_schema = 'fde_workbench'
   AND constraint_type = 'FOREIGN KEY';   -- 期望 35
SELECT table_name, table_comment FROM information_schema.tables
 WHERE table_schema = 'fde_workbench' ORDER BY table_name;
```

## 与本轮状态

`001_init.sql` 与 `002_demo_seed.sql` 已在目标实例 `192.168.2.125:3306`（MySQL 5.7.44）真实执行成功：19 张表、35 个外键，重复执行不产生变化。执行记录见 [database.md](../database.md#8-本轮实施记录)。
