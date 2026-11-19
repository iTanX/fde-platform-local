package com.fde.workbench.api.security;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.security.CurrentUserContext;
import org.springframework.stereotype.Component;

/**
 * 当前登录人的请求级上下文（ThreadLocal 实现）。
 * <p>
 * 由 {@link JwtAuthFilter} 在校验 token 通过后 {@link #set}，请求结束在 finally 中 {@link #clear}，
 * 避免线程池复用导致"上一个请求的用户"泄漏到下一个请求。
 */
@Component
public class CurrentUserHolder implements CurrentUserContext {

    /** 每个请求线程独享的当前用户 */
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    /**
     * 写入当前登录人（仅由认证过滤器调用）。
     *
     * @param user 当前登录人
     */
    public void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 清理上下文，必须在过滤器 finally 中调用 */
    public void clear() {
        HOLDER.remove();
    }

    @Override
    public CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            // 过滤器未放行却进入业务代码：统一 401/10101，由前端跳转登录页（PRD AC-H1.4）
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }
}
