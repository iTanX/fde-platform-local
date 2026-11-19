package com.fde.workbench.service;

import com.fde.workbench.dao.mapper.SystemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 系统信息服务：探测数据库连通性与 JDBC 会话时区。
 * <p>
 * 时区探测的由来：T0-1 指出"JDBC 会话时区未被证明"。数据库时间列统一按 +08:00 写入，
 * 若连接池里某个连接的会话时区不是 +08:00，"应用写入的 LocalDateTime"与库内时间就会错位。
 * 本类在启动完成时执行一次 {@code SELECT @@session.time_zone} 打日志，并由 /api/health 对外暴露，
 * 使该约定成为可观测、可断言的事实（测试见 workbench-app 的 SessionTimeZoneTest）。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    /** 期望的 JDBC 会话时区（docs/database.md §1） */
    public static final String EXPECTED_TIME_ZONE = "+08:00";

    /** 系统级 Mapper：连通性探测与时区探测 */
    private final SystemMapper systemMapper;

    public HealthService(SystemMapper systemMapper) {
        this.systemMapper = systemMapper;
    }

    /**
     * 探测数据库是否可用。
     *
     * @return true 表示 SELECT 1 成功
     */
    public boolean isDatabaseUp() {
        try {
            Integer one = systemMapper.selectOne();
            return one != null && one == 1;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 读取当前 JDBC 连接的会话时区。
     *
     * @return 形如 {@code +08:00} 的会话时区；数据库不可用时返回 null
     */
    public String sessionTimeZone() {
        try {
            return systemMapper.selectSessionTimeZone();
        } catch (RuntimeException ex) {
            log.warn("读取会话时区失败：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 启动完成后打印会话时区断言日志，便于运维与联调时一眼确认时区配置生效。
     */
    @EventListener(classes = ApplicationReadyEvent.class)
    public void logSessionTimeZoneOnStartup() {
        String zone = sessionTimeZone();
        if (zone == null) {
            log.warn("[启动检查] 数据库不可用，无法确认会话时区");
            return;
        }
        if (EXPECTED_TIME_ZONE.equals(zone)) {
            log.info("[启动检查] SELECT @@session.time_zone = {}（符合预期）", zone);
        } else {
            // 不中断启动：记录告警即可，实际影响是应用写入的时间与库内时区不一致
            log.warn("[启动检查] SELECT @@session.time_zone = {}，与预期 {} 不一致，请检查 JDBC URL 的 sessionVariables",
                    zone, EXPECTED_TIME_ZONE);
        }
    }
}
