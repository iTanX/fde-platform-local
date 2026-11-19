package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.NodeInputItem;
import com.fde.workbench.dao.entity.NodeOutputItem;
import com.fde.workbench.dao.entity.Workflow;
import com.fde.workbench.dao.entity.WorkflowNode;
import com.fde.workbench.dao.entity.WorkflowNodeRow;
import com.fde.workbench.dao.entity.WorkflowNodeSkill;
import com.fde.workbench.dao.entity.WorkflowNodeSkillDetail;
import com.fde.workbench.dao.entity.WorkflowWithStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程 Mapper，对应 {@code wb_workflow} 及其子表 {@code wb_workflow_node} /
 * {@code wb_workflow_node_skill} / {@code wb_node_input_item} / {@code wb_node_output_item}
 * （docs/api.md §6 流程，SQL 见 {@code mapper/WorkflowMapper.xml}）。
 * <p>
 * 为什么一个 Mapper 管五张表：这五张表构成"流程"这一个聚合根，整体保存节点时必须在同一事务内
 * 一起改；拆成五个 Mapper 只会让调用方来回穿针，且无法保证事务边界与级联顺序集中在一处。
 * <p>
 * <b>外键全部 RESTRICT</b>（脚本未指定 ON DELETE），因此删除顺序固定为：
 * 删子表（输出项 / 输入项 / 技能关联）→ 删节点（见 {@link #deleteNodeSkillsByWorkflow} 等三组的注释顺序）。
 * <p>
 * 本表没有 revision 列，所有"整体保存"的写操作都以 {@code updated_at} 相等作为并发比较条件。
 */
@Mapper
public interface WorkflowMapper {

    // ------------------------------------------------------------------
    // 流程（wb_workflow）
    // ------------------------------------------------------------------

    /**
     * 项目内未删除的流程列表，附带节点数（{@link WorkflowWithStats#getNodeCount()}）。
     *
     * @param projectId 项目 ID
     * @return 流程列表（创建时间倒序）
     */
    List<WorkflowWithStats> selectByProject(@Param("projectId") Long projectId);

    /**
     * 按主键查询未删除的流程。
     *
     * @param id 流程 ID
     * @return 流程；不存在 / 已删除返回 null
     */
    Workflow selectById(@Param("id") Long id);

    /**
     * 新建流程（{@code deleted=0}、时间列由应用写入）。
     *
     * @param workflow 流程实体（id 会回填）
     * @return 影响行数
     */
    int insert(Workflow workflow);

    /**
     * 更新名称 / 说明，并把 {@code updated_at} 推进到新时间（作为下一次并发比较的基准）。
     * <p>
     * 影响 0 行 = 版本不一致（{@code updated_at} 不等于提交的 revision 对应时间）或已被删除。
     *
     * @param id          流程 ID
     * @param name        新名称
     * @param description 新说明，可空
     * @param expected    期望的当前 {@code updated_at}（由 revision 换算）
     * @param updatedAt   新的 {@code updated_at}，必须严格大于 {@code expected}
     * @return 影响行数
     */
    int updateWithRevision(@Param("id") Long id,
                           @Param("name") String name,
                           @Param("description") String description,
                           @Param("expected") LocalDateTime expected,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 只推进 {@code updated_at}：整体保存节点成功后调用，让流程的 revision 随之变化。
     * <p>
     * 为什么要推进：节点的内容变化也是"流程被修改"，前端手里的旧 revision 应当立刻失效；
     * 否则两个人在同一 revision 下分别保存节点，后到者会覆盖前者而不报冲突。
     *
     * @param id        流程 ID
     * @param expected  期望的当前 {@code updated_at}
     * @param updatedAt 新的 {@code updated_at}，必须严格大于 {@code expected}
     * @return 影响行数；0 表示版本已变化，调用方应回滚整体保存
     */
    int touchUpdatedAt(@Param("id") Long id,
                       @Param("expected") LocalDateTime expected,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 逻辑删除流程（{@code deleted=1}）。
     * <p>
     * 只改标记、不删节点：{@code wb_requirement.workflow_id} 外键指向本表（RESTRICT），
     * 且历史需求仍要能追溯"来自哪个流程"。
     *
     * @param id        流程 ID
     * @param expected  期望的当前 {@code updated_at}
     * @param updatedAt 新的 {@code updated_at}
     * @return 影响行数；0 表示版本已变化或已被删除
     */
    int markDeleted(@Param("id") Long id,
                    @Param("expected") LocalDateTime expected,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 查询某流程下最近的 {@code limit} 个需求的工作区路径。
     * <p>
     * 用途（T4-1）：需求创建时先把 workspace 目录名定为自增 ID，但 MySQL 分配 ID 时不会告诉你
     * "这个 ID 以前是否被用掉过"。若靠可预测路径创建目录失败，需要确认"是不是另一个流程的需求
     * 刚好占用了这个 ID" 才能给出正确的 409 文案，而不是把磁盘错误当成 500。
     *
     * @param workflowId 流程 ID
     * @param limit      最多返回条数
     * @return 工作区路径列表（需求 ID 降序）
     */
    List<String> selectRecentWorkspacePaths(@Param("workflowId") Long workflowId,
                                            @Param("limit") int limit);

    // ------------------------------------------------------------------
    // 节点（wb_workflow_node）
    // ------------------------------------------------------------------

    /**
     * 流程下全部节点，按 {@code seq} 升序。
     *
     * @param workflowId 流程 ID
     * @return 节点列表
     */
    List<WorkflowNode> selectNodesByWorkflow(@Param("workflowId") Long workflowId);

    /**
     * 一次查出流程下全部节点及其技能关联 / 固定输入项 / 固定输出项（LEFT JOIN，见 {@link WorkflowNodeRow}）。
     *
     * @param workflowId 流程 ID
     * @return 节点 + 子项的展平行，按节点 {@code seq} 排序
     */
    List<WorkflowNodeRow> selectNodeDetails(@Param("workflowId") Long workflowId);

    /**
     * 流程下全部节点的技能关联 + 技能当前版本（供需求创建时写 {@code skills_snapshot}）。
     * <p>
     * 与 {@link #selectNodeDetails} 的区别：这里带上 {@code current_version_id} 与 {@code version_no}，
     * 且不做输入项 / 输出项的笛卡尔积（只 JOIN 技能与技能版本两张一对一关系的表），
     * 因此行数恰好等于"节点 × 技能"。
     *
     * @param workflowId 流程 ID
     * @return 技能行，按节点 seq、技能 seq 升序
     */
    List<WorkflowNodeSkillDetail> selectNodeSkillsDetail(@Param("workflowId") Long workflowId);

    /**
     * 新建节点。
     *
     * @param node 节点实体（id 会回填）
     * @return 影响行数
     */
    int insertNode(WorkflowNode node);

    /**
     * 更新节点内容（名称、说明、输入类型）并刷新 {@code updated_at}。
     * <p>
     * <b>不</b>在这里改 {@code seq}：重排统一走两步法（先挪到负数区间、再写正式序号），
     * 因为 {@code uk_node_workflow_seq(workflow_id, seq)} 是唯一索引，直接互换两个节点的 seq
     * 会中途撞索引（MySQL 唯一约束逐行校验，不做语句级延迟）。
     *
     * @param node 节点（只读 id / workflowId / name / description / inputType / updatedAt）
     * @return 影响行数
     */
    int updateNodeContent(WorkflowNode node);

    /**
     * 批量重排 {@code seq}（调用方保证同一流程内 {@code seq} 互不重复）。
     * <p>
     * 用于把节点挪到"临时负序号"区间（第一步）以及写入正式序号（第三步）；
     * 为什么必须整批一条语句、以及负数区/赋值顺序的约束，见 XML 中该语句的注释。
     * 参数列表为空时调用方不应调用本方法。
     *
     * @param workflowId 流程 ID（限制更新范围）
     * @param nodes      节点（使用 id / seq）
     * @param updatedAt  本次统一的更新时间（必须严格大于旧值，见 Service 中 revision 说明）
     * @return 影响行数
     */
    int updateNodeSeqs(@Param("workflowId") Long workflowId,
                       @Param("nodes") List<WorkflowNode> nodes,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 物理删除节点（调用方必须先删掉它的输出项、输入项、技能关联）。
     *
     * @param workflowId 流程 ID（防止跨流程误删）
     * @param nodeIds    节点 ID 列表
     * @return 删除行数
     */
    int deleteNodes(@Param("workflowId") Long workflowId, @Param("nodeIds") List<Long> nodeIds);

    // ------------------------------------------------------------------
    // 技能关联（wb_workflow_node_skill）
    // ------------------------------------------------------------------

    /**
     * 删除若干节点的技能关联。级联顺序第 1 步。
     *
     * @param nodeIds 节点 ID 列表
     * @return 删除行数
     */
    int deleteNodeSkills(@Param("nodeIds") List<Long> nodeIds);

    /**
     * 整体删除某流程全部节点的技能关联（{@code uk_node_skill(node_id, skill_id)} 是唯一索引，
     * 重排技能顺序必须先清空再按新顺序插入）。
     *
     * @param workflowId 流程 ID
     * @return 删除行数
     */
    int deleteNodeSkillsByWorkflow(@Param("workflowId") Long workflowId);

    /**
     * 插入节点技能关联。
     *
     * @param nodeSkill 关联实体（使用 nodeId / skillId / seq）
     * @return 影响行数
     */
    int insertNodeSkill(WorkflowNodeSkill nodeSkill);

    // ------------------------------------------------------------------
    // 固定输入项（wb_node_input_item）
    // ------------------------------------------------------------------

    /**
     * 删除若干节点的全部固定输入项。级联顺序第 2 步。
     *
     * @param nodeIds 节点 ID 列表
     * @return 删除行数
     */
    int deleteInputItems(@Param("nodeIds") List<Long> nodeIds);

    /**
     * 整体删除某流程全部节点的固定输入项（输入项没有稳定 key，整体保存时按新列表重建）。
     *
     * @param workflowId 流程 ID
     * @return 删除行数
     */
    int deleteInputItemsByWorkflow(@Param("workflowId") Long workflowId);

    /**
     * 插入固定输入项。
     *
     * @param item 输入项实体（id 会回填）
     * @return 影响行数
     */
    int insertInputItem(NodeInputItem item);

    // ------------------------------------------------------------------
    // 固定输出项（wb_node_output_item）
    // ------------------------------------------------------------------

    /**
     * 删除若干节点的全部固定输出项。级联顺序第 2 步。
     *
     * @param nodeIds 节点 ID 列表
     * @return 删除行数
     */
    int deleteOutputItems(@Param("nodeIds") List<Long> nodeIds);

    /**
     * 整体删除某流程全部节点的固定输出项。
     *
     * @param workflowId 流程 ID
     * @return 删除行数
     */
    int deleteOutputItemsByWorkflow(@Param("workflowId") Long workflowId);

    /**
     * 插入固定输出项。
     *
     * @param item 输出项实体（id 会回填）
     * @return 影响行数
     */
    int insertOutputItem(NodeOutputItem item);
}
