# 多格式拆分测试数据

用于验证「节点技能 + 产出闭环」的一组输入与技能包。

## 文件

| 文件 | 用途 |
|---|---|
| `多格式配置文档.md` | **节点输入**。一份文档内含三段内容，分别用 ```markdown、```json、```yaml 围栏包裹，顺序为 md → json → yaml |
| `multi-format-splitter.zip` | **节点技能**。上传到项目的技能 Tab，再在流程节点上选中 |
| `skill-format-splitter/SKILL.md` | 技能正文（zip 的解包内容，便于直接查看与修改） |

## 测试步骤

1. 项目 → 技能 Tab → 上传 `multi-format-splitter.zip`
2. 项目 → 流程 Tab → 编辑流程 → 某节点勾选「multi-format-splitter」技能
3. 该节点配置 3 个输出项（建议 `fileKind` 留空即 `any`）：
   - 名称「服务概述（Markdown）」
   - 名称「运行参数（JSON）」
   - 名称「分派规则（Properties）」
4. 新建需求 → 进节点 → 节点信息页上传 `多格式配置文档.md`
5. 会话里说：「请按技能把文档拆分成三份交付」

## 期望结果

| 段 | 输出 |
|---|---|
| Markdown 段 | 一份 `.md`，保留原标题层级 |
| JSON 段 | 一份 `.json`，2 空格缩进，字段与顺序不变 |
| YAML 段 | 一份 `.properties`，按简单扁平化：`dispatch.regions[0].name=cn-east` |

三份产出提交后在「节点信息 → 输出项」出现下载按钮。

## 注意

技能正文**不写输出项名称与写入路径**——那些由节点的输出项配置与内置技能
`workbench-node-delivery` 决定。本技能只负责「怎么拆」和「怎么转」。
