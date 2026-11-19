package com.fde.workbench.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 系统级 Mapper：提供连通性探测（SELECT 1）与会话时区探测（SELECT @@session.time_zone），不涉及任何业务表。
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

    /**
     * 会话时区探测：读取当前 JDBC 连接的会话时区。
     * <p>
     * 用于证明 JDBC URL 的 {@code sessionVariables=time_zone='+08:00'} 确实生效（T0-1 遗留待证明项）。
     *
     * @return 形如 {@code +08:00} 的会话时区；异常写法（如 SYSTEM）时返回其原始字符串
     */
    @Select("SELECT @@session.time_zone")
    String selectSessionTimeZone();
}
