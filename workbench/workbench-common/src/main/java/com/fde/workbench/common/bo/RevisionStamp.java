package com.fde.workbench.common.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 以"毫秒时间戳"表达的并发版本号（用于 {@code wb_workflow} 这类没有 revision 列的表）。
 * <p>
 * 背景：{@code wb_workflow} 只有 {@code updated_at DATETIME(3)}，没有 {@code revision INT}，
 * 而 T3-1 不允许改表。于是把 {@code updated_at} 换算成"UTC 毫秒时间戳"作为对外 revision：
 * <ul>
 *   <li>每次写都推到严格更大的值 → 单调、可比较；</li>
 *   <li>与 DB 精度（毫秒）一一对应 → 前端原样回传即可精确还原成 {@code DATETIME(3)}；</li>
 *   <li>放在 common 模块，是因为它<b>必须被 JSON 反序列化</b>（请求体里的 {@code revision: 1795103234307}
 *       要还原成对象），而 service 模块的 VO 都是"只序列化"的 record，不适合承担这个职责。</li>
 * </ul>
 * JSON 形态就是一个整数（{@link RevisionStampJackson}），与 api.md 中 {@code "revision": 2} 的形状一致。
 */
public final class RevisionStamp {

    /** 业务时区：库中时间按 +08:00 墙钟写入（docs/api.md §1.5） */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    /** UTC 毫秒时间戳 */
    private final long epochMilli;

    private RevisionStamp(long epochMilli) {
        this.epochMilli = epochMilli;
    }

    /**
     * 由库中时间构造。
     *
     * @param updatedAt 库中的 {@code updated_at}（+08:00 墙钟），可为 null
     * @return 版本；入参为 null 时返回 null（表示"没有版本信息"）
     */
    public static RevisionStamp of(LocalDateTime updatedAt) {
        return updatedAt == null ? null
                : new RevisionStamp(updatedAt.atZone(BIZ_ZONE).toInstant().toEpochMilli());
    }

    /**
     * 由毫秒时间戳构造（JSON 反序列化入口，见 {@link RevisionStampJackson}）。
     *
     * @param epochMilli UTC 毫秒时间戳
     * @return 版本
     */
    public static RevisionStamp ofEpochMilli(long epochMilli) {
        return new RevisionStamp(epochMilli);
    }

    /**
     * 还原成库中的 {@code updated_at}（+08:00，毫秒精度），供 SQL 做相等比较。
     *
     * @return 比较值
     */
    @JsonIgnore
    public LocalDateTime toUpdatedAt() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), BIZ_ZONE);
    }

    /** UTC 毫秒时间戳数值 */
    public long epochMilli() {
        return epochMilli;
    }

    /**
     * 由字符串构造：用于 {@code @RequestParam}（DELETE 的 {@code ?revision=N}）绑定。
     * <p>
     * Spring 对"非 String 的请求参数"会先找 {@code valueOf(String)} / 静态 {@link #of(String)}，
     * 找不到时退化成 Jackson 反序列化，而 Jackson 会按数字字面量解析出<b>错误的量级</b>
     * （早期版本缺少本方法时，{@code ?revision=1795103234307} 被解析成了 1969-12-29 的时间）。
     * 因此这里显式提供工厂方法，与请求体走"整数毫秒"的语义保持一致。
     *
     * @param text 十进制毫秒时间戳
     * @return 版本；空串返回 null
     * @throws NumberFormatException 不是十进制整数（由全局异常处理转成 400/10001）
     */
    public static RevisionStamp of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return new RevisionStamp(Long.parseLong(text.trim()));
    }

    @Override
    public String toString() {
        return String.valueOf(epochMilli);
    }
}
