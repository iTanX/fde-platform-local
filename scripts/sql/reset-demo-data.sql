-- 清理集成测试累积数据，只保留演示数据；删除顺序从叶子到根，遵守外键 RESTRICT。
USE fde_workbench;
SET time_zone = '+08:00';
START TRANSACTION;

-- 会话与节点实例（含需求）
DELETE FROM wb_node_session_link WHERE node_instance_id IN (SELECT id FROM wb_requirement_node);
DELETE FROM wb_session_runtime_info;
DELETE FROM wb_agent_session;
DELETE FROM wb_requirement_node_output;
DELETE FROM wb_requirement_node_input;
DELETE FROM wb_file;
DELETE FROM wb_requirement_node;
DELETE FROM wb_requirement;

-- 流程：只保留流程 1 及其子项
DELETE FROM wb_node_output_item WHERE node_id NOT IN (SELECT id FROM wb_workflow_node WHERE workflow_id = 1);
DELETE FROM wb_node_input_item  WHERE node_id NOT IN (SELECT id FROM wb_workflow_node WHERE workflow_id = 1);
DELETE FROM wb_workflow_node_skill WHERE node_id NOT IN (SELECT id FROM wb_workflow_node WHERE workflow_id = 1);
DELETE FROM wb_workflow_node WHERE workflow_id <> 1;
DELETE FROM wb_workflow WHERE id <> 1;

-- 技能：只保留技能 1 与版本 1
DELETE FROM wb_skill_version WHERE skill_id <> 1;
DELETE FROM wb_skill WHERE id <> 1;
UPDATE wb_skill SET deleted = 0, current_version_id = 1 WHERE id = 1;

-- 项目与成员
DELETE FROM wb_project_member WHERE project_id <> 1 OR user_id NOT IN (1, 2);
DELETE FROM wb_project WHERE id <> 1;

-- 用户：只保留 demo / demo2
DELETE FROM wb_user WHERE id NOT IN (1, 2);

COMMIT;
