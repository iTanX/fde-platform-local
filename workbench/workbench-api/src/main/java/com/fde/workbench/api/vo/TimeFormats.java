package com.fde.workbench.api.vo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 时间序列化工具：把库中按 +08:00 写入的 {@link LocalDateTime} 输出为 {@code 2026-11-19T10:00:00+08:00}。
 * <p>
 * 为什么不直接用 {@code @JsonFormat(pattern="...XXX", timezone="Asia/Shanghai")} 标注 LocalDateTime：
 * {@code LocalDateTime} 本身不含偏移，格式里的 {@code XXX} 无法取值（Jackson 会抛 "Unsupported field: OffsetSeconds"）。
 * 因此统一先补上业务时区再交给 {@code OffsetDateTime} 序列化，与 api.md §1.4 的示例格式一致。
 */
final class TimeFormats {

    /** 业务时区：与 JDBC 会话时区、application.yml 的 jackson.time-zone 保持一致 */
    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private TimeFormats() {
    }

    /** LocalDateTime → 业务时区下的 OffsetDateTime；null 透传 */
    static OffsetDateTime withOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(BIZ_ZONE).toOffsetDateTime();
    }

    /**
     * VO 中 LocalDateTime 字段的统一序列化器：输出带 +08:00 偏移的 ISO-8601 字符串。
     */
    static class BizOffsetSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(withOffset(value).toString());
        }
    }
}
