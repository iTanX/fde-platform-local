package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.AgentSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话 Mapper，对应 {@code wb_agent_session} / {@code wb_session_runtime_info} / {@code wb_node_session_link}
 * 三张表（docs/api.md §9、PRD REQ-X1/X2）。
 * <p>
 * 三张表放在一个 Mapper 里，原因与 {@code RequirementMapper} 相同：登记一次会话必须
 * <b>同一个事务</b>写三张表（会话 → 运行信息 → 节点绑定 → 弱关联），拆开只会让事务边界散落。
 * 物理删除需求的级联清理仍由 {@code RequirementMapper} 负责（那里已有相应语句）。
 */
@Mapper
public interface SessionMapper {

    /**
     * 插入会话。
     *
     * @param session 会话实体（id 会被回填）
     * @return 影响行数
     */
    int insertSession(AgentSession session);

    /**
     * 写入会话最后所在的节点，已登记的会话保留其余运行信息。
     *
     * @param agentSessionId     会话 ID
     * @param lastNodeInstanceId 最后所在节点，可空
     * @param now                时间
     * @return 影响行数
     */
    int saveRuntimeNode(@Param("agentSessionId") Long agentSessionId,
                        @Param("lastNodeInstanceId") Long lastNodeInstanceId,
                        @Param("now") LocalDateTime now);

    /**
     * 插入节点与会话的弱关联（已存在则忽略：同一会话在同一节点重复登记不应报错）。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param agentSessionId 会话 ID
     * @param now            时间
     * @return 影响行数（已存在返回 0）
     */
    int insertNodeSessionLink(@Param("nodeInstanceId") Long nodeInstanceId,
                              @Param("agentSessionId") Long agentSessionId,
                              @Param("now") LocalDateTime now);

    /**
     * 更新会话最近活跃时间。
     *
     * @param id  会话 ID
     * @param now 时间
     * @return 影响行数
     */
    int updateLastActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 按主键查会话。
     *
     * @param id 会话 ID
     * @return 会话；不存在返回 null
     */
    AgentSession selectById(@Param("id") Long id);

    /**
     * 按 DSH 会话标识查会话（用于 409 判定）。
     *
     * @param dshSessionId DSH 会话标识
     * @return 会话；不存在返回 null
     */
    AgentSession selectByDshSessionId(@Param("dshSessionId") String dshSessionId);

    /**
     * 需求下全部会话（按 {@code last_active_at} 倒序）。
     *
     * @param requirementId 需求 ID
     * @return 会话列表
     */
    List<AgentSession> selectByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 需求下 {@code archived=0} 且 {@code last_active_at} 最大的会话（默认会话解析第 2 条）。
     *
     * @param requirementId 需求 ID
     * @return 会话；没有符合条件的返回 null
     */
    AgentSession selectMostActiveByRequirement(@Param("requirementId") Long requirementId);
}
