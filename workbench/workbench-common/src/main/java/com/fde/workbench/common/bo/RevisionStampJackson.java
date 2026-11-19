package com.fde.workbench.common.bo;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * {@link RevisionStamp} 的 Jackson 绑定注册器：JSON 里就是一个整数（毫秒时间戳）。
 * <p>
 * 在 api 模块通过 {@code JacksonConfig#revisionStampModule()} 注册为 {@code Module} Bean，
 * Spring Boot 会自动挂到全局 {@code ObjectMapper}（线上接口与 MockMvc 测试共用同一个 Mapper）。
 */
public final class RevisionStampJackson {

    private RevisionStampJackson() {
    }

    /**
     * 构造包含 {@link RevisionStamp} 序列化 / 反序列化规则的模块。
     *
     * @return Jackson 模块
     */
    public static SimpleModule module() {
        SimpleModule module = new SimpleModule("workbench-revision-stamp");
        module.addSerializer(RevisionStamp.class, new RevisionStampIntSerializer());
        module.addDeserializer(RevisionStamp.class, new RevisionStampIntDeserializer());
        return module;
    }
}
