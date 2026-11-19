package com.fde.workbench.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.WorkbenchStorageProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权过滤器（docs/api.md §11、任务卡 T5-1）：{@code X-Internal-Token} + 仅本机来源。
 * <p>
 * <b>为什么需要独立的过滤器</b>：{@code /internal/**} 由插件（DSH Host 进程）调用，没有用户 JWT。
 * 身份换成"进程间共享令牌"，因此它既不经过 {@link LoginRequiredFilter}（只保护 {@code /api/**}），
 * 也不能靠 Service 里的 {@code currentUserContext.require()} 兜底——必须在进入 Controller 之前拦下。
 * <p>
 * <b>四道校验，任一不通过都返回 401/10103</b>：
 * <ol>
 *   <li><b>来源必须是本机</b>：{@code request.getRemoteAddr()} 解析后必须是回环地址。
 *       服务默认绑定 {@code 127.0.0.1}，但这属于"配置正确性"，不是"安全边界"；
 *       一旦有人把它绑到 {@code 0.0.0.0}，来源校验仍然拦住外网调用（纵深防御）；</li>
 *   <li><b>服务端令牌必须已配置</b>：{@code fde.workbench.internal-token} 默认是空串，
 *       <b>空令牌一律拒绝</b>——绝不允许"忘了配置就变成免鉴权"；</li>
 *   <li><b>请求头必须存在</b>：缺失即 401；</li>
 *   <li><b>常量时间比较</b>：{@link MessageDigest#isEqual} 保证比较耗时不随"前几位是否匹配"变化，
 *       避免通过响应时间逐字节猜令牌。</li>
 * </ol>
 * 401 的返回体是统一格式（{@code code=10103}），插件据此提示"internalToken 配置不对"。
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    /** 内部接口路径前缀 */
    public static final String INTERNAL_PREFIX = "/internal/";

    /** 令牌请求头（docs/api.md §11） */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final WorkbenchStorageProperties properties;

    private final ObjectMapper objectMapper;

    /** 是否已经提醒过"令牌未配置"（只提醒一次，避免刷日志） */
    private volatile boolean warnedMissingToken;

    public InternalAuthFilter(WorkbenchStorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只拦 /internal/**，其它路径直通（/api/** 由 LoginRequiredFilter 负责）
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isLoopback(request.getRemoteAddr())) {
            reject(request, response, "内部接口只接受本机调用");
            return;
        }
        String configured = properties.getInternalToken();
        if (configured == null || configured.isBlank()) {
            if (!warnedMissingToken) {
                warnedMissingToken = true;
                log.warn("fde.workbench.internal-token 未配置，/internal/** 全部拒绝（设置环境变量 FDE_INTERNAL_TOKEN）");
            }
            reject(request, response, "服务端未配置内部接口令牌");
            return;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || provided.isBlank()) {
            reject(request, response, "缺少 " + TOKEN_HEADER + " 请求头");
            return;
        }
        // 常量时间比较：比较耗时只与长度有关、与"第几位开始不同"无关，避免用响应耗时逐字节猜令牌。
        // 不做 trim —— 令牌必须逐字节一致，多一个空格就是错的
        boolean matched = MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            reject(request, response, "内部接口令牌无效");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 是否回环地址（127.0.0.0/8、::1、localhost）。
     *
     * @param remoteAddr 远端地址字符串
     * @return true 表示本机
     */
    static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            return address.isLoopbackAddress();
        } catch (IOException ex) {
            // 解析不出来（例如被反向代理改写成主机名）→ 按"不是本机"处理，宁可拒绝
            return false;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        log.warn("拒绝内部接口调用：{} {}，原因：{}", request.getMethod(), request.getRequestURI(), message);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResult<Void> body = ApiResult.fail(ErrorCode.INTERNAL_TOKEN_INVALID, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
