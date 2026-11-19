# Web 工作台 文档索引

基于 DeepSeek Harness 的 AI Web 工作台（项目中心 / 需求 / 流程 / 技能 / 成员）的设计文档集合。

## 阅读顺序

| 顺序 | 文档 | 读者 | 说明 |
|---|---|---|---|
| 0 | [原始需求](原始需求/Deepseek%20Harness%20工作台.md) | 全体 | 需求原文，未改动 |
| 1 | [PRD](PRD.md) | 产品 / 全体 | 需求 ID、验收标准（Given/When/Then）、权限矩阵、范围 |
| 2 | [架构设计](architecture.md) | 前后端 / 插件 | 总体结构、模块划分、数据流、技术决策 |
| 3 | [插件集成设计](plugin-integration.md) | 插件开发 / 后端 | DSH 接入方式、会话嵌入、模型工具、内置技能 |
| 4 | [数据库设计](database.md) | 后端 | 19 张表、关系、约束、存储路径 |
| 5 | [接口设计](api.md) | 前后端 | REST 接口、错误码、DTO、权限 |
| 6 | [开发计划](development-plan.md) | 全体 | 阶段 0~7、验收、风险 |
| 7 | [任务拆分与派发](task-decomposition.md) | 协调者 / 子代理 | 任务卡、依赖、波次、回传要求、完成状态 |

> **架构 v2**：业务界面以 DSH 插件页面的形式实现（`main` 面板渲染 React 组件），节点页内直接嵌入 DeepSeek Harness 原生会话，不使用 iframe 与跳转。详见 [architecture.md §2](architecture.md#2-总体结构)。

## 脚本

| 文件 | 说明 |
|---|---|
| [database/001_init.sql](database/001_init.sql) | 建库建表，可重复执行 |
| [database/002_demo_seed.sql](database/002_demo_seed.sql) | 演示数据（仅测试库） |
| [database/README.md](database/README.md) | 脚本变更规范 |

## 需求修正记录

相对原始需求，评审后确认的四处修正，已贯通 PRD、数据库、接口与插件设计：

| 编号 | 修正 | 落点 |
|---|---|---|
| M1 | 一个需求对应**多个会话**，会话按需求隔离，记录对应关系以支持后续会话恢复 | [PRD REQ-X1](PRD.md#req-x1-多会话与需求级隔离m1)、`wb_agent_session`、[api.md §9](api.md#9-会话-apirequirementsrequirementidsessions) |
| M2 | 节点推进后**默认沿用同一会话**，也可以新建会话 | [PRD AC-F7.3](PRD.md#req-f7-会话页签)、[database.md §5.2](database.md#52-多会话与会话恢复修正-m1m2) |
| M3 | 节点支持**多个 skill** | [PRD REQ-E5](PRD.md#e-流程管理)、`wb_workflow_node_skill`、`skills_snapshot` |
| M4 | **新建会话绑定到当时所在节点** | [PRD REQ-X2](PRD.md#req-x2-节点新建会话绑定节点m4)、`wb_requirement_node.primary_session_id`、[database.md §5.2](database.md#52-多会话与会话恢复修正-m1m2) |
| A-v2 | **会话在节点页内嵌入渲染**，不跳转、不用 iframe | [architecture.md §2.1](architecture.md#21-会话如何嵌在节点页里已核实官方能力)、[plugin-integration.md §4.1.1](plugin-integration.md#411-节点页里的会话如何嵌进来整页方案核心)、[PRD AC-F7.1](PRD.md#req-f7-会话页签) |

## 当前状态

| 项 | 状态 |
|---|---|
| 文档 | 已完成初版（draft），待评审 |
| 数据库脚本 | **已在 192.168.2.125:3306（MySQL 5.7.44）执行成功**：19 张表、35 个外键，重复执行不变；演示数据已写入 |
| 业务代码 | 未开始（架构已于 2026-11-19 修订为 v2 插件页方案） |
| 插件代码 | 未开始（已克隆 DSH 源码 `dsh-v0.2.2-rc.5` 到 `/tmp/dsh-src` 用于核对扩展点） |
