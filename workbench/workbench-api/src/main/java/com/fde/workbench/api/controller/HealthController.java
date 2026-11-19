package com.fde.workbench.api.controller;

import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.HealthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口：返回应用版本、数据库连通状态与 JDBC 会话时区。
 * <p>
 * 数据库不通时 HTTP 仍为 200，返回结构化 JSON，{@code data.db=down} 且 {@code code=19901}（服务内部错误）。
 * <p>
 * {@code data.sessionTimeZone} 用于证明 JDBC 的 {@code sessionVariables=time_zone='+08:00'} 生效
 * （T0-1 遗留的"会话时区未证明"项），正常值为 {@code +08:00}。
 */
@RestController
public class HealthController {

    /** 数据库状态：正常 */
    private static final String DB_UP = "ok";

    /** 数据库状态：异常 */
    private static final String DB_DOWN = "down";

    private final HealthService healthService;

    /** 应用版本，来自 Maven 构建写入的 build-info.properties */
    /** 应用版本：优先取 Maven 构建写入的 build-info.properties，兜底为 application.yml 的 info.app.version */
    private final String version;

    public HealthController(HealthService healthService,
                            @Value("${info.build.version:${info.app.version:unknown}}") String version) {
        this.healthService = healthService;
        this.version = version;
    }

    /**
     * GET /api/health —— 健康检查。
     *
     * @return 统一返回体，data 含 db / version
     */
    @GetMapping("/api/health")
    public ApiResult<Map<String, Object>> health() {
        boolean dbUp = healthService.isDatabaseUp();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("db", dbUp ? DB_UP : DB_DOWN);
        data.put("version", version);
        if (dbUp) {
            data.put("sessionTimeZone", healthService.sessionTimeZone());
            return ApiResult.ok(data);
        }
        return new ApiResult<>(ErrorCode.INTERNAL_ERROR.getCode(), "数据库连接不可用", data);
    }
}
