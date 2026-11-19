package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文件登记 Mapper，对应 {@code wb_file}（docs/api.md §10、docs/plugin-integration.md §5.1）。
 * <p>
 * 本 Mapper 只放"登记 / 查 / 删 / 回填关联 ID"这类单表语句；文件本体由 Service 通过
 * {@code WorkbenchStorage} 落盘，两者在同一个 Service 事务边界内编排。
 * <p>
 * 为什么需要"回填 ref_id"：文件行必须先插入才能拿到 {@code wb_file.id}（输入 / 输出记录要用它做外键），
 * 而业务记录（{@code wb_requirement_node_input/output}）的自增 ID 又是插入之后才知道的。
 * 因此顺序固定为：先插文件行 → 插业务记录 → 用业务记录 ID 回填 {@code ref_type/ref_id}，
 * 这三步都在同一个事务里，任何一步失败整体回滚，不会留下"文件行指向不存在的记录"。
 */
@Mapper
public interface FileMapper {

    /**
     * 插入文件登记行。
     *
     * @param file 文件实体（id 会被回填）
     * @return 影响行数
     */
    int insert(FileRecord file);

    /**
     * 按主键查文件登记。
     *
     * @param id 文件 ID
     * @return 文件；不存在返回 null
     */
    FileRecord selectById(@Param("id") Long id);

    /**
     * 回填业务引用（{@code ref_type} 与 {@code ref_id}）。
     *
     * @param id      文件 ID
     * @param refType 引用类型（node_input / node_output）
     * @param refId   业务记录 ID
     * @return 影响行数
     */
    int updateRef(@Param("id") Long id, @Param("refType") String refType, @Param("refId") Long refId);

    /**
     * 回填落盘后的真实存储路径、大小与摘要（{@code wb_file} 这三列在落盘后才确定）。
     *
     * @param id              文件 ID
     * @param storageRelPath  相对存储根的路径
     * @param sizeBytes       字节数
     * @param sha256          内容摘要
     * @return 影响行数
     */
    int updateStoredFile(@Param("id") Long id,
                         @Param("storageRelPath") String storageRelPath,
                         @Param("sizeBytes") Long sizeBytes,
                         @Param("sha256") String sha256);

    /**
     * 删除单个文件登记（删除节点输入时用；调用方须先删引用它的业务记录）。
     *
     * @param id 文件 ID
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id);

    /**
     * 删除某一需求下的全部文件登记（需求物理删除时的级联清理，必须在删节点与业务记录之后）。
     *
     * @param requirementId 需求 ID
     * @return 删除行数
     */
    int deleteByRequirement(@Param("requirementId") Long requirementId);

    /**
     * 列出某一需求下全部文件登记（需求物理删除前取磁盘路径，提交后按路径清理文件）。
     *
     * @param requirementId 需求 ID
     * @return 文件登记列表
     */
    List<FileRecord> selectByRequirement(@Param("requirementId") Long requirementId);
}
