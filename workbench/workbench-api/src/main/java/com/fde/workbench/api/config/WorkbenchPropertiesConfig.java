package com.fde.workbench.api.config;

import com.fde.workbench.service.WorkbenchStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 业务配置装配：把 {@code fde.workbench.*}（存储根、技能包大小限制）绑定到
 * {@link WorkbenchStorageProperties}。
 * <p>
 * 为什么不在 service 模块上直接加 {@code @ConfigurationProperties}：service 模块没有引入
 * spring-boot 依赖，{@code @EnableConfigurationProperties} 属于 Spring Boot 能力，放在 api 层最自然，
 * 且与启动类同在组件扫描范围内。
 */
@Configuration
@EnableConfigurationProperties(WorkbenchStorageProperties.class)
public class WorkbenchPropertiesConfig {
}
