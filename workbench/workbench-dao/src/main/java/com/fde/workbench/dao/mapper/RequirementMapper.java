package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.entity.RequirementNode;
import com.fde.workbench.dao.entity.RequirementWithStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求与节点实例 Mapper，对应 {@code wb_requirement} / {@code wb_requirement_node}
 * （docs/api.md §7，SQL 见 {@code mapper/RequirementMapper.xml}）。
 * <p>
 * 为什么一个 Mapper 管两张表：{@code wb_requirement_node} 是需求的"流程实例"，创建需求必须
 * 在同一事务内把需求行与全部节点实例写进去；拆成两个 Mapper 只会让事务边界散在两处。
 * 会话相关的三张表（{@code wb_agent_session} / {@code wb_session_runtime_info} /
 * {@code wb_node_session_link}）在<b>物理删除需求</b>时必须一起清理（外键 RESTRICT），
 * 这里只放"按需求 ID 定位并删除"这几条语句，会话的增删查本身属于 T5-1。
 * <p>
 * 演化轨迹：
 * <ul>
 *   <li>T3-1 引入 {@link #countByWorkflowId}（流程删除前的引用计数）；</li>
 *   <li>T4-1 在本接口上<b>扩展</b>需求的增删改查与节点实例写入，不另建第二套。</li>
 * </ul>
 */
@Mapper
public interface RequirementMapper {

    // ------------------------------------------------------------------
    // 需求（wb_requirement）
    // ------------------------------------------------------------------

    /**
     * 统计引用某流程的需求数（任何状态都算）。
     *
     * @param workflowId 流程 ID
     * @return 引用该流程的需求数
     */
    long countByWorkflowId(@Param("workflowId") Long workflowId);

    /**
     * 统计项目内需求数（列表分页的 total）。
     *
     * @param projectId 项目 ID
     * @param keyword   标题模糊词，可空（LIKE 的转义已在 XML 内完成）
     * @param status    状态过滤，可空
     * @return 需求数
     */
    long countByProject(@Param("projectId") Long projectId,
                        @Param("keyword") String keyword,
                        @Param("status") String status);

    /**
     * 项目内需求列表（分页，含流程名称与节点统计），按创建时间倒序。
     *
     * @param projectId 项目 ID
     * @param keyword   标题模糊词，可空
     * @param status    状态过滤，可空
     * @param offset    SQL 偏移量
     * @param limit     每页条数
     * @return 需求列表
     */
    List<RequirementWithStats> selectByProject(@Param("projectId") Long projectId,
                                               @Param("keyword") String keyword,
                                               @Param("status") String status,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

    /**
     * 按主键查询需求（含流程名称与节点统计）。
     *
     * @param id 需求 ID
     * @return 需求；不存在返回 null
     */
    RequirementWithStats selectById(@Param("id") Long id);

    /**
     * 插入需求行。
     * <p>
     * {@code dsh_workspace_path} 是 {@code NOT NULL}，而路径里要有自增 ID，所以调用方先用一个
     * 同事务内的占位值插入，拿到 ID 后再调 {@link #updateWorkspacePath} 回填
     * （详见 {@code RequirementService#create} 的注释）。
     *
     * @param requirement 需求实体（id 会被回填）
     * @return 影响行数
     */
    int insert(Requirement requirement);

    /**
     * 回填工作区绝对路径（创建需求的同一事务内调用）。
     *
     * @param id     需求 ID
     * @param path   工作区绝对路径
     * @param now    更新时间
     * @return 影响行数
     */
    int updateWorkspacePath(@Param("id") Long id,
                            @Param("path") String path,
                            @Param("now") LocalDateTime now);

    /**
     * 更新标题 / 说明并把 {@code revision} 推进 1（比较并交换）。
     * <p>
     * 影响 0 行 = 版本不一致（{@code revision} 不是期望值）或需求已不存在。
     *
     * @param id          需求 ID
     * @param title       新标题
     * @param description 新说明，可空
     * @param expected    期望的当前 revision
     * @param now         更新时间
     * @return 影响行数
     */
    int updateTitleAndDescription(@Param("id") Long id,
                                  @Param("title") String title,
                                  @Param("description") String description,
                                  @Param("expected") int expected,
                                  @Param("now") LocalDateTime now);

    /**
     * 物理删除需求行（调用方必须已按外键顺序清理全部子表）。
     *
     * @param id       需求 ID
     * @param revision 期望的当前 revision（删除的前置一致性校验）
     * @return 影响行数；0 表示 revision 不匹配或需求已不存在
     */
    int deleteById(@Param("id") Long id, @Param("revision") int revision);

    // ------------------------------------------------------------------
    // 节点实例（wb_requirement_node）
    // ------------------------------------------------------------------

    /**
     * 批量插入节点实例（快照已在 Service 里序列化成 JSON 字符串）。
     *
     * @param nodes 节点实例列表（id 会被回填）
     * @return 影响行数
     */
    int insertNodes(@Param("nodes") List<RequirementNode> nodes);

    /**
     * 需求下全部节点实例，按 {@code seq} 升序。
     *
     * @param requirementId 需求 ID
     * @return 节点实例列表
     */
    List<RequirementNode> selectNodesByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 节点实例总数（需求详情与列表的 {@code nodeCount}）。
     *
     * @param requirementId 需求 ID
     * @return 节点数
     */
    int countNodesByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 按主键查节点实例（T5-1 节点信息 / 上传 / 推进都要先拿到快照与状态）。
     *
     * @param id 节点实例 ID
     * @return 节点实例；不存在返回 null
     */
    RequirementNode selectNodeById(@Param("id") Long id);

    /**
     * 把当前节点置为 {@code done} 并写 {@code completed_at}（推进事务第一步）。
     * <p>
     * {@code completed_by} 只在为空时写：重复推进 / 补写时保留"第一次把它做完的人"，
     * 不让后到的调用者覆盖历史。
     *
     * @param id          节点实例 ID
     * @param completedBy 完成人，可空
     * @param now         完成时间
     * @return 影响行数
     */
    int markNodeDone(@Param("id") Long id,
                     @Param("completedBy") Long completedBy,
                     @Param("now") LocalDateTime now);

    /**
     * 把下一节点置为 {@code current} 并写 {@code started_at}（推进事务第二步）。
     * <p>
     * 只改状态为 {@code waiting} 的节点，避免把已完成的节点回退成进行中。
     *
     * @param id  下一节点实例 ID
     * @param now 开始时间
     * @return 影响行数
     */
    int markNodeCurrent(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 需求 {@code current_seq} 前进 1（推进事务第三步）。
     *
     * @param id  需求 ID
     * @param now 更新时间
     * @return 影响行数
     */
    int advanceCurrentSeq(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 需求状态置为 {@code completed}（最后一个节点完成时）。
     *
     * @param id  需求 ID
     * @param now 更新时间
     * @return 影响行数
     */
    int markRequirementCompleted(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 设置节点的默认会话（仅当 {@code primary_session_id} 为空时写入，PRD AC-X2.1）。
     *
     * @param id        节点实例 ID
     * @param sessionId 会话 ID
     * @return 影响行数；0 表示已绑定过或被删
     */
    int bindPrimarySessionIfAbsent(@Param("id") Long id, @Param("sessionId") Long sessionId);

    /**
     * 物理删除某需求的全部节点实例。
     * <p>
     * 调用前必须先清掉 {@code wb_requirement_node_input} / {@code wb_requirement_node_output}
     * （外键 RESTRICT）以及引用这些节点的 {@code wb_node_session_link} /
     * {@code wb_session_runtime_info}、{@code wb_requirement_node.primary_session_id}；
     * 删完节点后还要删 {@code wb_file}（T5-1 补齐，见 {@code RequirementService#delete}）。
     *
     * @param requirementId 需求 ID
     * @return 删除行数
     */
    int deleteNodesByRequirement(@Param("requirementId") Long requirementId);

    // ------------------------------------------------------------------
    // 会话关联（物理删除需求时的级联清理，会话本身的业务属于 T5-1）
    // ------------------------------------------------------------------

    /**
     * 把引用了本条需求节点的会话主键在节点上置空（{@code fk_req_node_primary_session} 是 RESTRICT，
     * 另外会先删节点再删会话，反向也必须在删会话前解除）。
     *
     * @param requirementId 需求 ID
     * @return 影响行数
     */
    int clearPrimarySessionByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 删除引用本条需求节点的会话运行时信息。
     *
     * @param requirementId 需求 ID
     * @return 删除行数
     */
    int deleteSessionRuntimeInfoByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 删除节点与会话的弱关联。
     *
     * @param requirementId 需求 ID
     * @return 删除行数
     */
    int deleteNodeSessionLinksByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 删除需求下的全部会话。
     *
     * @param requirementId 需求 ID
     * @return 删除行数
     */
    int deleteSessionsByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 统计需求下的会话数（删除前后的日志与必要校验）。
     *
     * @param requirementId 需求 ID
     * @return 会话数
     */
    int countSessionsByRequirement(@Param("requirementId") Long requirementId);
}
