package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.security.JwtUtil;
import com.fde.workbench.dao.entity.User;
import com.fde.workbench.dao.mapper.UserMapper;
import com.fde.workbench.service.bo.CurrentUser;
import com.fde.workbench.service.security.CurrentUserContext;
import com.fde.workbench.service.security.PasswordHasher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 认证服务：注册、登录、当前用户（docs/api.md §2，PRD REQ-H1）。
 * <p>
 * 业务规则集中在本类：
 * <ol>
 *   <li>用户名 3–32 位 {@code [A-Za-z0-9_]}；</li>
 *   <li>密码可为空——为空时 {@code password_hash} 存 NULL，且该用户只能以空密码登录；</li>
 *   <li>登录失败文案区分：用户不存在 → "用户不存在，请先注册"；密码错误 → "用户名或密码错误"，
 *       两者共同使用 10102（api.md §2 只对 10102 规定了这两种场景）。</li>
 * </ol>
 */
@Service
public class AuthService {

    /** 用户名规则：3–32 位字母、数字、下划线（与 api.md §2 一致） */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    /** 登录失败文案：用户不存在（api.md §2 明确要求这句） */
    static final String MSG_USER_NOT_FOUND = "用户不存在，请先注册";

    /** 登录失败文案：用户存在但密码不匹配 */
    static final String MSG_BAD_PASSWORD = "用户名或密码错误";

    /** 业务时区：expiresAt 以会话时区 +08:00 表达，与 JDBC sessionVariables 一致 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final UserMapper userMapper;

    private final PasswordHasher passwordHasher;

    private final JwtUtil jwtUtil;

    private final CurrentUserContext currentUserContext;

    public AuthService(UserMapper userMapper,
                       PasswordHasher passwordHasher,
                       JwtUtil jwtUtil,
                       CurrentUserContext currentUserContext) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.jwtUtil = jwtUtil;
        this.currentUserContext = currentUserContext;
    }

    /**
     * 注册。
     *
     * @param username    用户名，3–32 位字母数字下划线
     * @param password    密码，可空（空串 / null 均视为不设置密码）
     * @param displayName 展示名，可空则默认等于用户名
     * @return 新用户 ID
     * @throws BizException 用户名格式不合法 → 400/10001；用户名已存在 → 409/10401
     */
    @Transactional
    public Long register(String username, String password, String displayName) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "用户名须为 3–32 位字母、数字或下划线");
        }
        // 先查一次给出友好错误；并发注册时仍可能同时通过，插入再靠 uk_user_username 兜底
        if (userMapper.selectByUsername(username) != null) {
            throw BizException.of(ErrorCode.DUPLICATE_NAME, "用户名已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        // 空密码：不落任何哈希，存 NULL。这样"是否设过密码"由数据本身表达，也符合表结构注释
        user.setPasswordHash(hasPassword(password) ? passwordHasher.hash(password) : null);
        user.setDisplayName((displayName == null || displayName.isBlank()) ? username : displayName.trim());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 并发注册撞唯一索引：转换为同样的 10401，而不是 500
            throw BizException.of(ErrorCode.DUPLICATE_NAME, "用户名已存在");
        }
        return user.getId();
    }

    /**
     * 登录，签发 JWT。
     *
     * @param username 用户名
     * @param password 密码，可空
     * @return 登录结果（token、过期时刻、用户信息）
     * @throws BizException 401/10102，文案区分"用户不存在"与"密码错误"
     */
    public LoginResult login(String username, String password) {
        User user = (username == null || username.isBlank()) ? null : userMapper.selectByUsername(username);
        if (user == null) {
            // 未注册用户名：api.md §2 要求 message 必须是"用户不存在，请先注册"
            throw BizException.of(ErrorCode.BAD_CREDENTIALS, MSG_USER_NOT_FOUND);
        }
        if (user.getPasswordHash() == null) {
            // 空密码注册的用户没有任何哈希：只有传入空密码才放行（PRD AC-H1.3）
            if (hasPassword(password)) {
                throw BizException.of(ErrorCode.BAD_CREDENTIALS, MSG_BAD_PASSWORD);
            }
        } else if (!passwordHasher.matches(password, user.getPasswordHash())) {
            throw BizException.of(ErrorCode.BAD_CREDENTIALS, MSG_BAD_PASSWORD);
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        LocalDateTime expiresAt = LocalDateTime.ofInstant(jwtUtil.expiresAt(token), BIZ_ZONE)
                .truncatedTo(ChronoUnit.SECONDS);
        return new LoginResult(token, expiresAt, toCurrentUser(user));
    }

    /**
     * 当前登录人信息（/api/auth/me）。
     *
     * @return 当前用户
     * @throws BizException 未登录 401/10101；用户已被删除 401/10101（让前端重新登录）
     */
    public CurrentUser me() {
        CurrentUser login = currentUserContext.require();
        User user = userMapper.selectById(login.userId());
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录用户不存在，请重新登录");
        }
        return toCurrentUser(user);
    }

    /** 密码字段是否"有值"：null 与空白都视为空密码 */
    private static boolean hasPassword(String password) {
        return password != null && !password.isEmpty();
    }

    /** 实体 → 当前用户快照，避免把 Entity（含 password_hash）泄漏出 Service */
    private static CurrentUser toCurrentUser(User user) {
        return new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName());
    }

    /**
     * 登录结果。
     *
     * @param token     JWT
     * @param expiresAt 过期时刻（+08:00）
     * @param user      当前用户
     */
    public record LoginResult(String token, LocalDateTime expiresAt, CurrentUser user) {
    }
}
