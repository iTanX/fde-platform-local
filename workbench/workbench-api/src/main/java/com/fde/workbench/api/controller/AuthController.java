package com.fde.workbench.api.controller;

import com.fde.workbench.api.dto.LoginRequest;
import com.fde.workbench.api.dto.RegisterRequest;
import com.fde.workbench.api.vo.LoginVO;
import com.fde.workbench.api.vo.RegisterVO;
import com.fde.workbench.api.vo.UserVO;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（docs/api.md §2）：注册、登录、当前用户。
 * <p>
 * 注册与登录免登录（由 LoginRequiredFilter 放行），{@code /api/auth/me} 需要携带 token。
 */
@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /api/auth/register —— 注册（密码可为空）。
     *
     * @param request 注册请求 {@code {username, password?, displayName?}}
     * @return {@code {userId}}
     */
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<RegisterVO> register(@Valid @RequestBody RegisterRequest request) {
        Long userId = authService.register(request.username(), request.password(), request.displayName());
        return ApiResult.ok(new RegisterVO(userId));
    }

    /**
     * POST /api/auth/login —— 登录，返回 JWT。
     *
     * @param request 登录请求 {@code {username, password?}}
     * @return {@code {token, expiresAt, user}}
     */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(LoginVO.of(authService.login(request.username(), request.password())));
    }

    /**
     * GET /api/auth/me —— 当前登录用户。
     *
     * @return UserVO
     */
    @GetMapping("/me")
    public ApiResult<UserVO> me() {
        return ApiResult.ok(UserVO.of(authService.me()));
    }
}
