-- =============================================================================
-- DeepSeek Harness Web 工作台 —— 初始化脚本 001
-- 适用范围：MySQL 5.7.44（目标实例 192.168.2.125:3306 实测版本）
-- 执行方式：mysql -h <host> -P <port> -u <user> <pwd> < docs/database/001_init.sql
-- 说明：
--   1. 本脚本可重复执行（IF NOT EXISTS / CREATE INDEX 前先判存在），不会删除任何已有数据。
--   2. 为兼容 MySQL 5.7，本脚本刻意不使用以下 8.0 特性：
--      - utf8mb4_0900_ai_ci 排序规则（改用 utf8mb4_unicode_ci）
--      - 函数/表达式默认值（如 CURRENT_TIMESTAMP(3)）、多列索引 DESC
--      - CHECK 约束、窗口函数、VALUES 表构造函数
--   3. 所有时间列使用 DATETIME(3)，默认值由应用写入；因此 JDBC 必须显式设置时间。
--   4. 可编辑聚合根带 revision 乐观锁；需"删除"语义的业务表使用 deleted（0/1）。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `fde_workbench`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `fde_workbench`;

-- 统一会话时区，多实例写库时间一致；JDBC 需配置 sessionVariables=time_zone='%2B08:00'
SET time_zone = '+08:00';

-- -----------------------------------------------------------------------------
-- 1. 用户
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键',
  `username`      VARCHAR(64)  NOT NULL COMMENT '登录用户名，注册时唯一',
  `password_hash` VARCHAR(255) NULL     COMMENT 'BCrypt 哈希；注册允许空密码，空密码用户此处为 NULL',
  `display_name`  VARCHAR(64)  NOT NULL COMMENT '展示名称，默认等于用户名',
  `created_at`    DATETIME(3)  NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`    DATETIME(3)  NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';

-- -----------------------------------------------------------------------------
-- 2. 项目
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_project` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '项目主键',
  `name`        VARCHAR(128)  NOT NULL COMMENT '项目名称',
  `description` VARCHAR(1000) NULL     COMMENT '项目简介',
  `owner_id`    BIGINT        NOT NULL COMMENT '项目 owner 用户 ID，与 wb_project_member 中 role=owner 的记录一致',
  `revision`    INT           NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，每次修改 +1',
  `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
  `created_at`  DATETIME(3)   NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`  DATETIME(3)   NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  KEY `idx_project_owner` (`owner_id`),
  KEY `idx_project_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目：数据顶层维度';

-- -----------------------------------------------------------------------------
-- 3. 项目成员
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_project_member` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT      NOT NULL,
  `user_id`    BIGINT      NOT NULL,
  `role`       VARCHAR(16) NOT NULL COMMENT 'owner 或 member；一个项目有且只有一个 owner',
  `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_project_user` (`project_id`, `user_id`),
  KEY `idx_member_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员与角色';

-- -----------------------------------------------------------------------------
-- 4. 技能与技能版本
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_skill` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `project_id`         BIGINT       NOT NULL,
  `name`               VARCHAR(128) NOT NULL COMMENT '技能显示名',
  `description`        VARCHAR(500) NULL     COMMENT '技能用途，取自 SKILL.md frontmatter',
  `current_version_id` BIGINT       NULL     COMMENT '当前生效版本；上传新版本后指向最新版本',
  `created_by`         BIGINT       NOT NULL,
  `deleted`            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除；被未完成节点引用时禁止删除',
  `created_at`         DATETIME(3)  NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`         DATETIME(3)  NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  KEY `idx_skill_project` (`project_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目技能：Agent 的 skill';

CREATE TABLE IF NOT EXISTS `wb_skill_version` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `skill_id`    BIGINT       NOT NULL,
  `version_no`  INT          NOT NULL COMMENT '同一技能内递增，从 1 开始',
  `files_json`  JSON         NOT NULL COMMENT '文件清单：[{"path":"SKILL.md","size":123,"sha256":".."}]',
  `checksum`    CHAR(64)     NOT NULL COMMENT '整体内容摘要（各文件 sha256 排序后再摘要）',
  `entry_path`  VARCHAR(255) NOT NULL DEFAULT 'SKILL.md' COMMENT '技能入口文件，供加载时读取',
  `uploaded_by` BIGINT       NOT NULL,
  `created_at`  DATETIME(3)  NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_version` (`skill_id`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能版本：保留历史，节点实例按版本号快照';

-- -----------------------------------------------------------------------------
-- 5. 流程与节点
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_workflow` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `project_id`  BIGINT        NOT NULL,
  `name`        VARCHAR(128)  NOT NULL,
  `description` VARCHAR(1000) NULL,
  `created_by`  BIGINT        NOT NULL,
  `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除；被需求引用时禁止删除',
  `created_at`  DATETIME(3)   NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`  DATETIME(3)   NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  KEY `idx_workflow_project` (`project_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程：节点按 seq 自上而下执行';

CREATE TABLE IF NOT EXISTS `wb_workflow_node` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `workflow_id` BIGINT        NOT NULL,
  `seq`         INT           NOT NULL COMMENT '执行顺序，从 1 开始，同一流程内唯一',
  `name`        VARCHAR(128)  NOT NULL COMMENT '节点名称',
  `description` VARCHAR(1000) NULL     COMMENT '节点说明，进入节点信息页展示',
  `input_type`  VARCHAR(16)   NOT NULL COMMENT 'free=自由输入（整节点上传），fixed=固定输入项（逐项上传）',
  `created_at`  DATETIME(3)   NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`  DATETIME(3)   NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_workflow_seq` (`workflow_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程节点';

-- 节点可用技能：多对多，支持一个节点配置多个 skill（对应修正 M3）
CREATE TABLE IF NOT EXISTS `wb_workflow_node_skill` (
  `id`       BIGINT NOT NULL AUTO_INCREMENT,
  `node_id`  BIGINT NOT NULL,
  `skill_id` BIGINT NOT NULL,
  `seq`      INT    NOT NULL DEFAULT 1 COMMENT '提示与加载顺序',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_skill` (`node_id`, `skill_id`),
  KEY `idx_node_skill_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点可用技能（多选）';

-- 固定输入项（仅 input_type=fixed 时存在）
CREATE TABLE IF NOT EXISTS `wb_node_input_item` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `node_id`     BIGINT       NOT NULL,
  `seq`         INT          NOT NULL DEFAULT 1,
  `name`        VARCHAR(128) NOT NULL COMMENT '输入项名称',
  `description` VARCHAR(500) NULL     COMMENT '输入项说明',
  `file_kind`   VARCHAR(16)  NOT NULL DEFAULT 'any' COMMENT 'text=md/txt，document=docx/pdf/xlsx/pptx，any=不限',
  `required`    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否必填；非必填项允许跳过直接推进',
  `created_at`  DATETIME(3)  NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  KEY `idx_input_item_node` (`node_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点固定输入项';

-- 固定输出项（必填项未全部提交时节点不得推进）
CREATE TABLE IF NOT EXISTS `wb_node_output_item` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `node_id`     BIGINT       NOT NULL,
  `seq`         INT          NOT NULL DEFAULT 1,
  `item_key`    VARCHAR(64)  NOT NULL COMMENT '输出项稳定键，模型工具按此键提交；节点内唯一',
  `name`        VARCHAR(128) NOT NULL COMMENT '输出项名称',
  `description` VARCHAR(500) NULL     COMMENT '输出项要求，进入模型上下文',
  `file_kind`   VARCHAR(16)  NOT NULL DEFAULT 'any' COMMENT 'text/document/any',
  `required`    TINYINT      NOT NULL DEFAULT 1,
  `created_at`  DATETIME(3)  NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_output_item_key` (`node_id`, `item_key`),
  KEY `idx_output_item_node` (`node_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点固定输出项';

-- -----------------------------------------------------------------------------
-- 6. 需求与流程实例
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_requirement` (
  `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
  `project_id`         BIGINT        NOT NULL,
  `workflow_id`        BIGINT        NOT NULL COMMENT '绑定的流程，创建后不可更换',
  `title`              VARCHAR(200)  NOT NULL,
  `description`        TEXT          NULL,
  `creator_id`         BIGINT        NOT NULL COMMENT '需求创建人；只有创建人能推进流程',
  `status`             VARCHAR(16)   NOT NULL DEFAULT 'in_progress' COMMENT 'in_progress / completed',
  `current_seq`        INT           NOT NULL DEFAULT 1 COMMENT '当前节点序号，对应 wb_requirement_node.seq',
  `dsh_workspace_path` VARCHAR(1000) NOT NULL COMMENT '该需求对应的 DSH workspace 绝对路径（一个需求一个 workspace）',
  `revision`           INT           NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
  `created_at`         DATETIME(3)   NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`         DATETIME(3)   NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  KEY `idx_requirement_project` (`project_id`, `status`),
  KEY `idx_requirement_workflow` (`workflow_id`),
  KEY `idx_requirement_creator` (`creator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求：创建即启动流程实例';

-- 需求节点实例：创建需求时对流程节点做快照，后续修改流程不影响已有需求
CREATE TABLE IF NOT EXISTS `wb_requirement_node` (
  `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
  `requirement_id`     BIGINT        NOT NULL,
  `workflow_node_id`   BIGINT        NOT NULL COMMENT '来源流程节点，仅用于追溯',
  `seq`                INT           NOT NULL,
  `name`               VARCHAR(128)  NOT NULL COMMENT '快照：节点名称',
  `description`        VARCHAR(1000) NULL     COMMENT '快照：节点说明',
  `input_type`         VARCHAR(16)   NOT NULL COMMENT '快照：free / fixed',
  `inputs_snapshot`    JSON          NOT NULL COMMENT '快照：输入项 [{"itemId":1,"name":"..","fileKind":"..","required":true}]',
  `outputs_snapshot`   JSON          NOT NULL COMMENT '快照：输出项 [{"itemId":1,"itemKey":"..","name":"..","fileKind":"..","required":true}]',
  `skills_snapshot`    JSON          NOT NULL COMMENT '快照：技能 [{"skillId":1,"name":"..","versionId":2,"versionNo":1}]，多技能',
  `primary_session_id` BIGINT        NULL     COMMENT '本节点默认打开的会话，见修正 M4',
  `status`             VARCHAR(16)   NOT NULL DEFAULT 'waiting' COMMENT 'waiting / current / done',
  `started_at`         DATETIME(3)   NULL COMMENT '成为当前节点的时间',
  `completed_at`       DATETIME(3)   NULL COMMENT '全部必填输出项提交完成的时间',
  `completed_by`       BIGINT        NULL,
  `created_at`         DATETIME(3)   NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_req_node_seq` (`requirement_id`, `seq`),
  KEY `idx_req_node_status` (`requirement_id`, `status`),
  KEY `idx_req_node_primary_session` (`primary_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求节点实例';

-- -----------------------------------------------------------------------------
-- 7. 文件登记（项目内所有上传与产出文件）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_file` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT,
  `project_id`       BIGINT        NOT NULL,
  `requirement_id`   BIGINT        NULL     COMMENT '归属需求；技能包文件为 NULL',
  `ref_type`         VARCHAR(32)   NOT NULL COMMENT 'skill_file / node_input / node_output / free_input',
  `ref_id`           BIGINT        NULL     COMMENT '对应业务记录 ID',
  `original_name`    VARCHAR(255)  NOT NULL,
  `storage_rel_path` VARCHAR(1000) NOT NULL COMMENT '相对 fde.workbench.storage-root 的路径',
  `mime_type`        VARCHAR(128)  NULL,
  `size_bytes`       BIGINT        NOT NULL DEFAULT 0,
  `sha256`           CHAR(64)      NOT NULL COMMENT '内容摘要，用于校验与版本比对',
  `uploaded_by`      BIGINT        NOT NULL,
  `created_at`       DATETIME(3)   NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  KEY `idx_file_project` (`project_id`),
  KEY `idx_file_ref` (`ref_type`, `ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件登记';

CREATE TABLE IF NOT EXISTS `wb_requirement_node_input` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT,
  `node_instance_id` BIGINT      NOT NULL,
  `input_item_id`    BIGINT      NULL COMMENT '固定输入项 ID；自由输入时为 NULL',
  `file_id`          BIGINT      NOT NULL,
  `uploaded_by`      BIGINT      NOT NULL,
  `created_at`       DATETIME(3) NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  KEY `idx_node_input_node` (`node_instance_id`, `input_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点输入文件';

CREATE TABLE IF NOT EXISTS `wb_requirement_node_output` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT,
  `node_instance_id` BIGINT      NOT NULL,
  `output_item_id`   BIGINT      NOT NULL COMMENT '输出项来源 ID（流程节点上的输出项）',
  `item_key`         VARCHAR(64) NOT NULL COMMENT '输出项稳定键快照，模型工具按此键提交',
  `file_id`          BIGINT      NOT NULL,
  `version_no`       INT         NOT NULL DEFAULT 1 COMMENT '同一输出项的提交版本，重新提交会递增',
  `committed_by`     BIGINT      NOT NULL,
  `committed_at`     DATETIME(3) NOT NULL COMMENT '提交时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_output_item` (`node_instance_id`, `item_key`),
  KEY `idx_node_output_file` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点输出项（唯一约束保证一个输出项只有一个当前版本）';

-- -----------------------------------------------------------------------------
-- 8. DSH 会话与需求的对应关系（一对一需求 → 多会话，对应修正 M1/M2/M4）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_agent_session` (
  `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
  `requirement_id`     BIGINT        NOT NULL COMMENT '会话归属需求；一个需求可有多个会话',
  `dsh_session_id`     VARCHAR(128)  NOT NULL COMMENT 'DSH 会话标识，会话恢复的唯一依据',
  `dsh_workspace_path` VARCHAR(1000) NOT NULL COMMENT '会话所在 workspace（与需求的 workspace 相同，冗余便于重链）',
  `title`              VARCHAR(200)  NULL COMMENT '会话标题，从 DSH 会话摘要同步',
  `created_by`         BIGINT        NOT NULL,
  `last_active_at`     DATETIME(3)   NOT NULL COMMENT '最近活跃时间，用于默认恢复会话选择',
  `archived`           TINYINT       NOT NULL DEFAULT 0 COMMENT '1 时不再作为默认会话，但仍可在列表中查看',
  `created_at`         DATETIME(3)   NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`         DATETIME(3)   NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_session_dsh` (`dsh_session_id`),
  KEY `idx_agent_session_req` (`requirement_id`, `archived`, `last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求与 DSH 会话的对应关系';

-- 会话恢复所需的运行信息：记录会话最后所在节点与恢复时要复用的 DSH 装载参数
CREATE TABLE IF NOT EXISTS `wb_session_runtime_info` (
  `id`                    BIGINT      NOT NULL AUTO_INCREMENT,
  `agent_session_id`      BIGINT      NOT NULL,
  `last_node_instance_id` BIGINT      NULL COMMENT '该会话最后所在的节点实例，见修正 M2',
  `agent_preset_id`       VARCHAR(64) NULL COMMENT 'DSH 会话记录的 Agent 预设；恢复时按其创建，缺失则用默认预设',
  `last_prompt_at`        DATETIME(3) NULL COMMENT '最后一次向该会话提交消息的时间',
  `message_count`         INT         NOT NULL DEFAULT 0 COMMENT '已提交的消息轮数，仅作展示与排序参考',
  `created_at`            DATETIME(3) NOT NULL COMMENT '创建时间，应用写入',
  `updated_at`            DATETIME(3) NOT NULL COMMENT '更新时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_runtime_session` (`agent_session_id`),
  KEY `idx_session_runtime_node` (`last_node_instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话恢复运行信息';

-- 节点实例与产生过对话的会话的弱关联，仅用于恢复定位与展示
CREATE TABLE IF NOT EXISTS `wb_node_session_link` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT,
  `node_instance_id` BIGINT      NOT NULL,
  `agent_session_id` BIGINT      NOT NULL,
  `created_at`       DATETIME(3) NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_session_link` (`node_instance_id`, `agent_session_id`),
  KEY `idx_node_session_link_session` (`agent_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='节点与会话弱关联';

-- -----------------------------------------------------------------------------
-- 9. 内置指导技能登记（每个节点默认注入，见 docs/plugin-integration.md）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wb_builtin_skill` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(128) NOT NULL COMMENT '内置技能名称，与插件注册名一致',
  `description` VARCHAR(500) NOT NULL,
  `version`     VARCHAR(32)  NOT NULL COMMENT '随插件发布的版本号',
  `created_at`  DATETIME(3)  NOT NULL COMMENT '创建时间，应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_builtin_skill_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内置指导技能登记';

INSERT INTO `wb_builtin_skill` (`name`, `description`, `version`, `created_at`)
SELECT 'workbench-node-delivery', '指导模型按当前节点要求产出固定输出项，并写入可被页面识别和下载的位置', '1', NOW(3)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `wb_builtin_skill` WHERE `name` = 'workbench-node-delivery');

-- -----------------------------------------------------------------------------
-- 10. 外键（单独 AFTER 建表，便于分步排查；重复执行前逐个判存在）
-- -----------------------------------------------------------------------------
-- 说明：目标实例很可能不允许在 ON DELETE 外键上使用 CASCADE 之外的复杂级联，
-- 此处全部使用 RESTRICT，引用清理由 Service 层事务显式完成，避免误删级联。
DROP PROCEDURE IF EXISTS `wb_add_fk`;
DELIMITER $$
CREATE PROCEDURE `wb_add_fk`(
  IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_ddl VARCHAR(500)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table AND CONSTRAINT_NAME = p_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD CONSTRAINT `', p_name, '` ', p_ddl);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL `wb_add_fk`('wb_project', 'fk_project_owner', 'FOREIGN KEY (`owner_id`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_project_member', 'fk_member_project', 'FOREIGN KEY (`project_id`) REFERENCES `wb_project` (`id`)');
CALL `wb_add_fk`('wb_project_member', 'fk_member_user', 'FOREIGN KEY (`user_id`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_skill', 'fk_skill_project', 'FOREIGN KEY (`project_id`) REFERENCES `wb_project` (`id`)');
CALL `wb_add_fk`('wb_skill', 'fk_skill_creator', 'FOREIGN KEY (`created_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_skill_version', 'fk_skill_version_skill', 'FOREIGN KEY (`skill_id`) REFERENCES `wb_skill` (`id`)');
CALL `wb_add_fk`('wb_skill_version', 'fk_skill_version_uploader', 'FOREIGN KEY (`uploaded_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_workflow', 'fk_workflow_project', 'FOREIGN KEY (`project_id`) REFERENCES `wb_project` (`id`)');
CALL `wb_add_fk`('wb_workflow', 'fk_workflow_creator', 'FOREIGN KEY (`created_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_workflow_node', 'fk_node_workflow', 'FOREIGN KEY (`workflow_id`) REFERENCES `wb_workflow` (`id`)');
CALL `wb_add_fk`('wb_workflow_node_skill', 'fk_node_skill_node', 'FOREIGN KEY (`node_id`) REFERENCES `wb_workflow_node` (`id`)');
CALL `wb_add_fk`('wb_workflow_node_skill', 'fk_node_skill_skill', 'FOREIGN KEY (`skill_id`) REFERENCES `wb_skill` (`id`)');
CALL `wb_add_fk`('wb_node_input_item', 'fk_input_item_node', 'FOREIGN KEY (`node_id`) REFERENCES `wb_workflow_node` (`id`)');
CALL `wb_add_fk`('wb_node_output_item', 'fk_output_item_node', 'FOREIGN KEY (`node_id`) REFERENCES `wb_workflow_node` (`id`)');
CALL `wb_add_fk`('wb_requirement', 'fk_requirement_project', 'FOREIGN KEY (`project_id`) REFERENCES `wb_project` (`id`)');
CALL `wb_add_fk`('wb_requirement', 'fk_requirement_workflow', 'FOREIGN KEY (`workflow_id`) REFERENCES `wb_workflow` (`id`)');
CALL `wb_add_fk`('wb_requirement', 'fk_requirement_creator', 'FOREIGN KEY (`creator_id`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_requirement_node', 'fk_req_node_requirement', 'FOREIGN KEY (`requirement_id`) REFERENCES `wb_requirement` (`id`)');
CALL `wb_add_fk`('wb_requirement_node', 'fk_req_node_completer', 'FOREIGN KEY (`completed_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_requirement_node', 'fk_req_node_primary_session', 'FOREIGN KEY (`primary_session_id`) REFERENCES `wb_agent_session` (`id`)');
CALL `wb_add_fk`('wb_file', 'fk_file_project', 'FOREIGN KEY (`project_id`) REFERENCES `wb_project` (`id`)');
CALL `wb_add_fk`('wb_file', 'fk_file_requirement', 'FOREIGN KEY (`requirement_id`) REFERENCES `wb_requirement` (`id`)');
CALL `wb_add_fk`('wb_file', 'fk_file_uploader', 'FOREIGN KEY (`uploaded_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_requirement_node_input', 'fk_node_input_node', 'FOREIGN KEY (`node_instance_id`) REFERENCES `wb_requirement_node` (`id`)');
CALL `wb_add_fk`('wb_requirement_node_input', 'fk_node_input_file', 'FOREIGN KEY (`file_id`) REFERENCES `wb_file` (`id`)');
CALL `wb_add_fk`('wb_requirement_node_input', 'fk_node_input_uploader', 'FOREIGN KEY (`uploaded_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_requirement_node_output', 'fk_node_output_node', 'FOREIGN KEY (`node_instance_id`) REFERENCES `wb_requirement_node` (`id`)');
CALL `wb_add_fk`('wb_requirement_node_output', 'fk_node_output_file', 'FOREIGN KEY (`file_id`) REFERENCES `wb_file` (`id`)');
CALL `wb_add_fk`('wb_requirement_node_output', 'fk_node_output_committer', 'FOREIGN KEY (`committed_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_agent_session', 'fk_agent_session_requirement', 'FOREIGN KEY (`requirement_id`) REFERENCES `wb_requirement` (`id`)');
CALL `wb_add_fk`('wb_agent_session', 'fk_agent_session_creator', 'FOREIGN KEY (`created_by`) REFERENCES `wb_user` (`id`)');
CALL `wb_add_fk`('wb_session_runtime_info', 'fk_session_runtime_session', 'FOREIGN KEY (`agent_session_id`) REFERENCES `wb_agent_session` (`id`)');
CALL `wb_add_fk`('wb_session_runtime_info', 'fk_session_runtime_node', 'FOREIGN KEY (`last_node_instance_id`) REFERENCES `wb_requirement_node` (`id`)');
CALL `wb_add_fk`('wb_node_session_link', 'fk_link_node', 'FOREIGN KEY (`node_instance_id`) REFERENCES `wb_requirement_node` (`id`)');
CALL `wb_add_fk`('wb_node_session_link', 'fk_link_session', 'FOREIGN KEY (`agent_session_id`) REFERENCES `wb_agent_session` (`id`)');

DROP PROCEDURE IF EXISTS `wb_add_fk`;
