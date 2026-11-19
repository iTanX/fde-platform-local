package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.NodeOutputRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点输出 Mapper，对应 {@code wb_requirement_node_output}（docs/api.md §11 commit、
 * docs/database.md §5.4 输出项版本）。
 * <p>
 * {@link #upsertCommit} 是推进事务的核心写入：唯一键
 * {@code uk_node_output_item(node_instance_id, item_key)} 命中时把 {@code version_no + 1}
 * 并改指向新文件；新文件行由调用方在同一事务里先插入（旧 {@code wb_file} 行保留）。
 */
@Mapper
public interface NodeOutputMapper {

    /**
     * 登记一次提交：不存在则插入 v1，已存在则 {@code version_no + 1} 并换文件。
     * <p>
     * 用 {@code ON DUPLICATE KEY UPDATE} 而不是"先查后写"：并发两次提交同一输出项时，
     * 前者由唯一键把并发串行化（后到者拿到加一后的版本号），后者会出现两个 v1 或覆盖丢版本。
     * <p>
     * <b>不要把本语句的 {@code last_insert_id} 当作记录主键</b>：MySQL 5.7 在"插入被改成更新"时
     * 也会推进 AUTO_INCREMENT 并把新值作为 last_insert_id 返回，那是一个并不存在的行 ID
     * （T5-1 实测踩到：同一节点的两条输出记录拿到了同一个 id）。写入后的行由调用方再用
     * {@link #selectByNodeAndKey} 读一次（同一事务内，读到的一定是刚写入的版本）。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param outputItemId   输出项来源 ID（快照）
     * @param itemKey        输出项稳定键
     * @param fileId         本次提交的文件登记 ID
     * @param committedBy    提交人
     * @param committedAt    提交时间
     * @return 影响行数（插入 1、更新 2，MySQL 语义；调用方不依赖具体值）
     */
    int upsertCommit(@Param("nodeInstanceId") Long nodeInstanceId,
                     @Param("outputItemId") Long outputItemId,
                     @Param("itemKey") String itemKey,
                     @Param("fileId") Long fileId,
                     @Param("committedBy") Long committedBy,
                     @Param("committedAt") LocalDateTime committedAt);

    /**
     * 某节点实例的全部输出记录（含当前文件展示字段），按主键升序。
     *
     * @param nodeInstanceId 节点实例 ID
     * @return 输出记录列表
     */
    List<NodeOutputRecord> selectByNode(@Param("nodeInstanceId") Long nodeInstanceId);

    /**
     * 某节点实例的某个输出项当前版本。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param itemKey        输出项稳定键
     * @return 记录；从未提交返回 null
     */
    NodeOutputRecord selectByNodeAndKey(@Param("nodeInstanceId") Long nodeInstanceId,
                                        @Param("itemKey") String itemKey);

    /**
     * 删除某需求的全部输出记录（需求物理删除时的级联清理，必须在删节点之前）。
     *
     * @param requirementId 需求 ID
     * @return 删除行数
     */
    int deleteByRequirement(@Param("requirementId") Long requirementId);
}
