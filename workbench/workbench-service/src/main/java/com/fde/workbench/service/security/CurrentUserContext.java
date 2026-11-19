package com.fde.workbench.service.security;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.service.bo.CurrentUser;

/**
 * 当前登录人上下文（请求级，实现放在 api 层，基于 ThreadLocal）。
 * <p>
 * Service 层只依赖本接口：需要"我是谁"时调用 {@link #require()}，从而与 HTTP / Servlet 解耦。
 */
public interface CurrentUserContext {

    /**
     * 取当前登录人。
     *
     * @return 当前登录人
     * @throws BizException 未携带有效 token 时抛 401 / 10101
     */
    CurrentUser require();
}
