package com.fde.workbench.dao.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 配置入口：扫描 {@code com.fde.workbench.dao.mapper} 下的 Mapper 接口。
 * <p>
 * 下划线转驼峰（MapUnderscoreToCamelCase）与 Mapper XML 位置在 application.yml 的
 * {@code mybatis.configuration.map-underscore-to-camel-case} 与 {@code mybatis.mapper-locations} 中声明。
 */
@Configuration
@MapperScan("com.fde.workbench.dao.mapper")
public class MybatisConfig {
}
