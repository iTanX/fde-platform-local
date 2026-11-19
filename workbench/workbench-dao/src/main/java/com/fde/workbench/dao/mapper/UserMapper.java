package com.fde.workbench.dao.mapper;

import com.fde.workbench.dao.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 Mapper，对应表 {@code wb_user}；SQL 见 {@code mapper/UserMapper.xml}。
 */
@Mapper
public interface UserMapper {

    /**
     * 按用户名查询（用于登录与注册去重）。
     *
     * @param username 用户名（调用方已做格式校验）
     * @return 用户；不存在返回 null
     */
    User selectByUsername(@Param("username") String username);

    /**
     * 按主键查询（用于 /api/auth/me）。
     *
     * @param id 用户 ID
     * @return 用户；不存在返回 null
     */
    User selectById(@Param("id") Long id);

    /**
     * 插入用户。
     * <p>
     * 时间列由应用写入（表结构无默认值）；插入成功后自增主键回填到 {@code user.id}。
     *
     * @param user 用户实体
     * @return 影响行数
     */
    int insert(User user);
}
