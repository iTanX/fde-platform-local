package com.fde.workbench.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 系统级 Mapper：目前仅提供连通性探测（SELECT 1），不涉及任何业务表。
 */
@Mapper
public interface SystemMapper {

    /**
     * 连通性探测：执行 SELECT 1。
     *
     * @return 固定返回 1；数据库不可达时抛出 DataAccessException
     */
    @Select("SELECT 1")
    Integer selectOne();
}
