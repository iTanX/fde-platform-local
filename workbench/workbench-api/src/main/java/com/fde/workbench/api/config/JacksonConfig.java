package com.fde.workbench.api.config;

import com.fde.workbench.common.bo.RevisionStampJackson;
import com.fasterxml.jackson.databind.Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口层 Jackson 定制：注册 {@link com.fde.workbench.common.bo.RevisionStamp} 的"整数"序列化规则。
 * <p>
 * 为什么要显式注册：revision 在 JSON 里是整数（例 {@code "revision": 1795103234307}），
 * 而不是 {@code {"epochMilli": ...}} 这样的对象；同时请求体里的 revision 也必须能反序列化回对象。
 * 注册成 {@link Module} Bean 后 Spring Boot 会把它自动挂到全局 {@code ObjectMapper} 上，
 * 测试里的 MockMvc 与线上接口走的是同一个 ObjectMapper，行为一致。
 */
@Configuration
public class JacksonConfig {

    /**
     * revision 模块（序列化为整数 / 只接受 JSON 整数）。
     *
     * @return Jackson 模块
     */
    @Bean
    public Module revisionStampModule() {
        return RevisionStampJackson.module();
    }
}
