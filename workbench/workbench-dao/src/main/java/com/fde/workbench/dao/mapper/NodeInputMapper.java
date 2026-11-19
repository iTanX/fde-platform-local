package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.NodeInputFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 节点输入文件 Mapper，对应 {@code wb_requirement_node_input}（docs/api.md §8 POST inputs、
 * docs/database.md §5.3 推进条件第 2 条）。
 * <p>
 * 与 {@link FileMapper} 的分工：本表记录"哪个节点收到了哪个文件"，文件元数据在 {@code wb_file}。
 * 查询语句统一 join {@code wb_file} 一次取回展示字段，避免列表页 N+1。
 */
@Mapper
public interface NodeInputMapper {

    /**
     * 插入输入记录。
     *
     * @param input 输入记录（id 会被回填）
     * @return 影响行数
     */
    int insert(NodeInputFile input);

    /**
     * 按主键查输入记录（含文件展示字段）。
     *
     * @param id 输入记录 ID
     * @return 记录；不存在返回 null
     */
    NodeInputFile selectById(@Param("id") Long id);

    /**
     * 某节点实例的全部输入记录（按主键升序 = 上传先后），含文件展示字段。
     *
     * @param nodeInstanceId 节点实例 ID
     * @return 输入记录列表
     */
    List<NodeInputFile> selectByNode(@Param("nodeInstanceId") Long nodeInstanceId);

    /**
     * 统计某节点下的输入文件数（推进判定：free 节点至少 1 个）。
     *
     * @param nodeInstanceId 节点实例 ID
     * @return 文件数
     */
    int countByNode(@Param("nodeInstanceId") Long nodeInstanceId);

    /**
     * 统计某固定输入项已上传的文件数（推进判定：required 固定输入项必须 ≥1）。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param inputItemId    固定输入项 ID
     * @return 文件数
     */
    int countByNodeAndItem(@Param("nodeInstanceId") Long nodeInstanceId,
                           @Param("inputItemId") Long inputItemId);

    /**
     * 删除单个输入记录（返回影响行数，调用方据此判定"已经被删过"）。
     *
     * @param id             输入记录 ID
     * @param nodeInstanceId 节点实例 ID（一并限定，防止跨节点删别人的记录）
     * @param fileId         对应文件登记 ID（一并限定，保证拿到的就是这条记录的文件）
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id,
                   @Param("nodeInstanceId") Long nodeInstanceId,
                   @Param("fileId") Long fileId);

    /**
     * 删除某需求的全部节点输入记录（需求物理删除时的级联清理，必须在删节点之前）。
     *
     * @param requirementId 需求 ID
     * @return 删除行数
     */
    int deleteByRequirement(@Param("requirementId") Long requirementId);
}
