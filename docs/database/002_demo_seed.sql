-- =============================================================================
-- DeepSeek Harness Web 工作台 —— 演示数据种子（可选）
-- 适用范围：MySQL 5.7.44；必须先执行 001_init.sql
-- 用途：阶段 1~6 联调时无需手工录入，直接得到一个可登录、可推进的完整演示项目。
-- 注意：
--   1. 时间列没有数据库默认值（与 001_init.sql 保持一致，由应用写入），
--      本脚本统一用 NOW(3) / @now 显式填充。
--   2. 重复执行安全：使用固定主键 + INSERT IGNORE / 存在性判断。
--   3. password_hash 为 NULL，表示"注册时未设置密码"，只能以空密码登录；
--      真实密码哈希由注册流程生成。
--   4. 生产环境不得执行本脚本；如需演示数据请在测试库执行。
-- =============================================================================

USE `fde_workbench`;
SET time_zone = '+08:00';
SET @now = NOW(3);

-- 演示用户
INSERT IGNORE INTO `wb_user` (`id`, `username`, `password_hash`, `display_name`, `created_at`, `updated_at`)
VALUES
  (1, 'demo',  NULL, '演示 Owner', @now, @now),
  (2, 'demo2', NULL, '演示成员',   @now, @now);

-- 演示项目：demo 为 owner，demo2 为普通成员
INSERT IGNORE INTO `wb_project` (`id`, `name`, `description`, `owner_id`, `revision`, `deleted`, `created_at`, `updated_at`)
VALUES (1, '演示项目：客户工单智能分诊', '用于联调 Web 工作台主流程的演示项目', 1, 1, 0, @now, @now);

INSERT IGNORE INTO `wb_project_member` (`id`, `project_id`, `user_id`, `role`, `created_at`)
VALUES
  (1, 1, 1, 'owner',  @now),
  (2, 1, 2, 'member', @now);

-- 演示技能：一个技能包，版本 1
INSERT IGNORE INTO `wb_skill` (`id`, `project_id`, `name`, `description`, `current_version_id`, `created_by`, `deleted`, `created_at`, `updated_at`)
VALUES (1, 1, '需求拆解', '把客户原话拆成可验收的需求条目与边界条件', NULL, 1, 0, @now, @now);

INSERT IGNORE INTO `wb_skill_version` (`id`, `skill_id`, `version_no`, `files_json`, `checksum`, `entry_path`, `uploaded_by`, `created_at`)
VALUES (
  1, 1, 1,
  JSON_ARRAY(JSON_OBJECT('path', 'SKILL.md', 'size', 1024, 'sha256', REPEAT('a', 64))),
  REPEAT('a', 64), 'SKILL.md', 1, @now
);

UPDATE `wb_skill` SET `current_version_id` = 1
 WHERE `id` = 1 AND `current_version_id` IS NULL;

-- 演示流程：三个节点，第二个节点为固定输入 + 两个输出项
INSERT IGNORE INTO `wb_workflow` (`id`, `project_id`, `name`, `description`, `created_by`, `deleted`, `created_at`, `updated_at`)
VALUES (1, 1, '需求分析标准流程', '澄清 -> 拆解 -> 确认，自上而下执行', 1, 0, @now, @now);

INSERT IGNORE INTO `wb_workflow_node` (`id`, `workflow_id`, `seq`, `name`, `description`, `input_type`, `created_at`, `updated_at`)
VALUES
  (1, 1, 1, '需求澄清', '收集客户原话与背景资料，形成问题清单', 'free',  @now, @now),
  (2, 1, 2, '需求拆解', '基于澄清结果拆出需求条目与验收标准',   'fixed', @now, @now),
  (3, 1, 3, '需求确认', '核对需求条目，输出确认结论',           'free',  @now, @now);

INSERT IGNORE INTO `wb_workflow_node_skill` (`id`, `node_id`, `skill_id`, `seq`)
VALUES (1, 2, 1, 1);

INSERT IGNORE INTO `wb_node_input_item` (`id`, `node_id`, `seq`, `name`, `description`, `file_kind`, `required`, `created_at`)
VALUES
  (1, 2, 1, '客户原话整理', '第一阶段澄清后整理的客户原话',     'text', 1, @now),
  (2, 2, 2, '现场资料清单', '可选：现场设备与系统清单',         'any',  0, @now);

INSERT IGNORE INTO `wb_node_output_item` (`id`, `node_id`, `seq`, `item_key`, `name`, `description`, `file_kind`, `required`, `created_at`)
VALUES
  (1, 2, 1, 'requirement-items',   '需求条目清单', '逐条列出需求编号、描述与来源',     'text', 1, @now),
  (2, 2, 2, 'acceptance-criteria', '验收标准',     '每条需求至少一条可验证的验收标准', 'text', 1, @now);

-- 第 1 节点（自由输入）也给一个输出项，便于在第一个节点就能验证产出闭环
INSERT IGNORE INTO `wb_node_output_item` (`id`, `node_id`, `seq`, `item_key`, `name`, `description`, `file_kind`, `required`, `created_at`)
VALUES (3, 1, 1, 'clarify-notes', '澄清记录', '记录客户原话、问题清单与待确认事项', 'text', 1, @now);

-- 第 3 节点同样必须有输出项：流程保存接口要求「每个节点至少 1 个输出项」，
-- 种子数据缺这一项会导致该流程无法通过 API 原样保存或还原。
INSERT IGNORE INTO `wb_node_output_item` (`id`, `node_id`, `seq`, `item_key`, `name`, `description`, `file_kind`, `required`, `created_at`)
VALUES (4, 3, 1, 'confirm-summary', '确认结论', '记录需求确认结论、残留问题与下一步', 'text', 1, @now);
