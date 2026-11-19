package com.fde.workbench.service.vo;

import java.util.List;

/**
 * 需求节点实例的快照结构（docs/database.md §5.1/§5.5、api.md §1.4 RequirementNodeVO 的 {@code inputs}/{@code outputs}/{@code skills}）。
 * <p>
 * <b>快照语义（为什么要有这一层）</b>：需求创建时把流程节点的输入项、输出项、技能<b>复制</b>进
 * {@code wb_requirement_node} 的三个 JSON 列；此后流程模板怎么改都不影响已有需求
 * （PRD AC-F2.2）。因此这里的 record 是"实例视图"，字段名与 JSON 键名是接口契约的一部分：
 * <ul>
 *   <li>JSON 键名即 record 组件名（驼峰，与 docs/database.md §5.1 与 api.md §1.4 的示例一致）；</li>
 *   <li>{@code skills[].skillId} 是<b>删除技能的引用匹配键</b>（api.md §5 引用判定口径），不能省；</li>
 *   <li>{@code skills[].versionId/versionNo} 取"创建那一刻的技能当前版本"，之后技能升级也不回改。</li>
 * </ul>
 * 一次请求内由同一个 record 负责序列化（写入快照）与反序列化（读回快照），避免两处漂移。
 */
public final class NodeSnapshots {

    private NodeSnapshots() {
    }

    /**
     * 输入项快照：{@code [{"itemId":1,"name":"..","fileKind":"..","required":true}]}。
     * <p>
     * {@code input_type=free} 时为空数组（自由输入整个节点一个上传入口，没有固定输入项）。
     *
     * @param itemId   输入项 ID（uploads 时用它绑定固定输入项）
     * @param name     名称
     * @param fileKind text / document / any
     * @param required 是否必填
     */
    public record InputItem(Long itemId, String name, String fileKind, Boolean required) {
    }

    /**
     * 输出项快照：{@code [{"itemId":1,"itemKey":"..","name":"..","fileKind":"..","required":true}]}。
     * <p>
     * {@code itemKey} 必须快照：模型工具按它提交产出（{@code .workbench/<nodeId>/<itemKey>.<ext>}），
     * 流程模板里改 key 不能让历史需求的提交路径跟着变。
     *
     * @param itemId   输出项 ID
     * @param itemKey  稳定键（节点内唯一）
     * @param name     名称
     * @param fileKind text / document / any
     * @param required 是否必填（必填项未全部提交前节点不推进）
     */
    public record OutputItem(Long itemId, String itemKey, String name, String fileKind, Boolean required) {
    }

    /**
     * 技能快照：{@code [{"skillId":1,"name":"..","versionId":2,"versionNo":1}]}，多技能按配置顺序。
     *
     * @param skillId   技能 ID（删除校验的匹配键）
     * @param name      技能名称（创建时的名称，技能改名后仍显示旧名）
     * @param versionId 技能当前版本 ID；技能尚无版本时为 null
     * @param versionNo 技能当前版本号；技能尚无版本时为 null
     */
    public record SkillItem(Long skillId, String name, Long versionId, Integer versionNo) {
    }

    /**
     * 一个节点的完整快照（三个 JSON 列）。
     *
     * @param inputs  输入项快照（free 时为空）
     * @param outputs 输出项快照
     * @param skills  技能快照（可空列表）
     */
    public record NodeSnapshot(List<InputItem> inputs, List<OutputItem> outputs, List<SkillItem> skills) {
    }
}
