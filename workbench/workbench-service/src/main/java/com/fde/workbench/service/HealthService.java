package com.fde.workbench.service;

import com.fde.workbench.dao.mapper.SystemMapper;
import org.springframework.stereotype.Service;

/**
 * 健康检查服务：通过 SystemMapper 探测数据库连通性，供 /api/health 使用。
 */
@Service
public class HealthService {

    /** 系统级 Mapper，仅用于 SELECT 1 探测 */
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
}
