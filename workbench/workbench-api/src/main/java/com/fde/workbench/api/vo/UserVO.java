package com.fde.workbench.api.vo;

import com.fde.workbench.service.bo.CurrentUser;

/**
 * 用户视图对象（docs/api.md §1.4 UserVO）：只暴露 id / username / displayName，绝不包含 password_hash。
 *
 * @param id          用户 ID
 * @param username    用户名
 * @param displayName 展示名
 */
public record UserVO(Long id, String username, String displayName) {

    /**
     * 由当前用户快照转换。
     *
     * @param user 当前用户
     * @return 视图对象
     */
    public static UserVO of(CurrentUser user) {
        return new UserVO(user.userId(), user.username(), user.displayName());
    }
}
