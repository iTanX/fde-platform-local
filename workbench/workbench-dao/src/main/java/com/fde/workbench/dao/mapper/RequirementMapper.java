package com.fde.workbench.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 需求表 {@code wb_requirement} 的只读查询：只承载"引用计数"，不承载需求业务逻辑。
 * <p>
 * 本接口由 T3-1 引入，<b>只</b>提供"流程被多少个需求引用"这一条计数语句：流程删除前必须据此判断
 * 是否可删（{@code 409/10403}，docs/api.md §6 DELETE）。需求的增删改查、节点实例快照属于 T4-1，
 * 届时由 T4-1 在本接口上扩展其它语句，T3-1 不改动、也不预置任何需求写操作。
 * <p>
 * 引用口径（与 api.md §6 "被需求引用时 409 10403"一致）：
 * <ul>
 *   <li>统计 {@code wb_requirement.workflow_id = 流程 ID} 的全部需求，<b>不区分</b>需求状态——</li>
 *   <li>已完成的需求同样算引用：外键 {@code fk_requirement_workflow} 是 RESTRICT，物理删除也做不到，
 *       而且历史需求要靠它追溯"来自哪个流程"；逻辑删除流程会让历史需求的来源变成悬空引用。</li>
 * </ul>
 */
@Mapper
public interface RequirementMapper {

    /**
     * 统计引用某流程的需求数（任何状态都算）。
     *
     * @param workflowId 流程 ID
     * @return 引用该流程的需求数
     */
    long countByWorkflowId(@Param("workflowId") Long workflowId);
}
