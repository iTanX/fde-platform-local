package com.fde.workbench.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Service 层的业务时间序列化工具：把 {@link LocalDateTime} 输出为 {@code 2026-11-19T10:00:00+08:00}。
 * <p>
 * 为什么不复用 {@code com.fde.workbench.api.vo.TimeFormats}：api 模块依赖 service，反向引用会破坏
 * 模块依赖方向（app → api → service → dao → common）。技能 VO 由 Service 直接转换（需要把
 * {@code files_json} 解析成结构化文件清单），因此序列化注解只能落在 service 模块里。
 * 时区常量与 api 层保持一致（{@link ZoneId#of(String) Asia/Shanghai}），两处输出格式相同。
 */
public final class ServiceTimeFormats {

    /** 业务时区：与 JDBC 会话时区、application.yml 的 jackson.time-zone 保持一致 */
    public static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private ServiceTimeFormats() {
    }

    /** VO 中 LocalDateTime 字段的统一序列化器：输出带 +08:00 偏移的 ISO-8601 字符串。 */
    public static class BizOffsetSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.atZone(BIZ_ZONE).toOffsetDateTime().toString());
        }
    }
}
