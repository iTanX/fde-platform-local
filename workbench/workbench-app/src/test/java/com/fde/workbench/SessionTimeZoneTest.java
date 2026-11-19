package com.fde.workbench;

import com.fde.workbench.dao.mapper.SystemMapper;
import com.fde.workbench.service.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 时区断言测试：证明 JDBC 会话时区确实是 {@code +08:00}。
 * <p>
 * 背景：T0-1 遗留项指出"JDBC 会话时区未被证明"。时间列由应用写入 {@code LocalDateTime.now()}，
 * 若连接上的会话时区不是 +08:00（例如服务端默认 UTC 或 SYSTEM），写入值与读出的值会相差 8 小时。
 * 本测试直接对真实数据库执行 {@code SELECT @@session.time_zone}，并验证 MyBatis / JdbcTemplate
 * 两条访问路径看到的是同一时区。
 */
@SpringBootTest
class SessionTimeZoneTest {

    /** 期望的会话时区（docs/database.md §1） */
    private static final String EXPECTED = "+08:00";

    @Autowired
    private HealthService healthService;

    @Autowired
    private SystemMapper systemMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Mapper 路径：SELECT @@session.time_zone 必须是 +08:00 */
    @Test
    void mybatisSessionTimeZone_isPlus08() {
        System.out.println("[时区断言] SELECT @@session.time_zone (MyBatis) = " + systemMapper.selectSessionTimeZone());
        assertEquals(EXPECTED, systemMapper.selectSessionTimeZone(),
                "JDBC URL 的 sessionVariables=time_zone='+08:00' 未生效");
    }

    /** JdbcTemplate 路径：与 MyBatis 共用连接池，结论必须一致 */
    @Test
    void jdbcTemplateSessionTimeZone_isPlus08() {
        String zone = jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class);
        System.out.println("[时区断言] SELECT @@session.time_zone (JdbcTemplate) = " + zone);
        assertEquals(EXPECTED, zone);
    }

    /** 服务层对外暴露的断言值（同时被 /api/health 输出），必须与库内一致 */
    @Test
    void healthServiceSessionTimeZone_isPlus08() {
        String zone = healthService.sessionTimeZone();
        System.out.println("[时区断言] HealthService.sessionTimeZone() = " + zone);
        assertEquals(EXPECTED, zone);
        assertEquals(EXPECTED, HealthService.EXPECTED_TIME_ZONE);
    }

    /**
     * 交叉校验：库内 NOW() 与 JVM 在 Asia/Shanghai 下的当前时间必须接近。
     * <p>
     * 若会话时区是 UTC 而 JVM 是 +08:00，两者会稳定相差 8 小时，本断言即可暴露问题。
     */
    @Test
    void applicationLocalDateTime_matchesDatabaseNow() {
        // 同一事务内取数据库 NOW() 与 JVM 当前时间的差值；两者都在 +08:00 下才会接近
        Long diffSeconds = jdbcTemplate.queryForObject(
                "SELECT ABS(TIMESTAMPDIFF(SECOND, NOW(), ?))",
                Long.class,
                // 以 +08:00 换算成 OffsetDateTime 再交给驱动，避免 JVM 时区影响断言
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        System.out.println("[时区断言] ABS(TIMESTAMPDIFF(SECOND, NOW(), JVM+08:00 now)) = " + diffSeconds);
        // 允许 60 秒内的网络/执行抖动
        org.junit.jupiter.api.Assertions.assertTrue(diffSeconds != null && diffSeconds <= 60,
                "数据库 NOW() 与 JVM 的 +08:00 当前时间相差 " + diffSeconds + " 秒，会话时区可能不是 +08:00");
    }
}
