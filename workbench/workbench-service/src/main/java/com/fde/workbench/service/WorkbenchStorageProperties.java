package com.fde.workbench.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储、大小限制与内部接口令牌配置，对应 {@code application.yml} 的 {@code fde.workbench.*}（docs/architecture.md §10）。
 * <p>
 * 为什么要做成配置项而不是常量：单文件 / 总量限制随部署环境不同（本地演示 20/100 MiB 够用，
 * 生产可能收紧或放宽），存储根也要能从 {@code ./data} 换到挂载盘；写死会导致换环境就要改代码。
 * <ul>
 *   <li>{@code storage-root}：存储根（相对路径按启动时的工作目录解析），对应 docs/database.md §5.5；</li>
 *   <li>{@code skill.max-file-bytes}：技能包内单个文件上限，默认 20 MiB；</li>
 *   <li>{@code skill.max-total-bytes}：技能包解包后总量上限，默认 100 MiB；
 *       它同时是解包时的"防 zip 炸弹"上限（见 {@code SkillPackageExtractor}）。</li>
 *   <li>{@code internal-token}：{@code /internal/**} 的令牌，默认空（未配置即拒绝），不写死明文；</li>
 *   <li>{@code max-upload-bytes}：节点输入单文件上限（T5-1），默认 20 MiB；</li>
 *   <li>{@code node-output-max-bytes}：模型输出单文件上限（T5-1），默认 10 MiB；</li>
 *   <li>{@code allowed-origins}：允许跨源访问 {@code /api/**} 的浏览器来源（CORS），默认本机 dsh web
 *       的 {@code http://127.0.0.1:3080} 与 {@code http://localhost:3080}（端到端阻塞问题：浏览器直连后端）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "fde.workbench")
public class WorkbenchStorageProperties {

    /** 存储根目录，默认 ./data（相对启动目录解析），不写死绝对路径 */
    private String storageRoot = "./data";

    /** 技能包相关限制 */
    private Skill skill = new Skill();

    /**
     * 内部接口令牌（T5-1）：插件 Host 调用 {@code /internal/**} 时放在 {@code X-Internal-Token} 头里。
     * <p>
     * 默认值故意留空、并带上"未配置即拒绝"的语义（见 {@code InternalAuthFilter}）：
     * <b>仓库里不出现可用的明文令牌</b>。本地开发用环境变量 {@code FDE_INTERNAL_TOKEN} 注入，
     * 插件侧通过自己的 {@code Config.internalToken} 配同一个值。
     */
    private String internalToken = "";

    /** 节点输入上传的单文件上限（字节），默认 20 MiB；超过返回 413/10501 */
    private long maxUploadBytes = 20L * 1024 * 1024;

    /** 节点启动器输出单文件上限（字节），默认 10 MiB；提交时超过返回 413/10501 */
    private long nodeOutputMaxBytes = 10L * 1024 * 1024;

    /**
     * 环境变量名：覆盖允许来源（逗号分隔）。
     * <p>
     * <b>为什么不用 Spring 的 relaxed binding</b>：{@code FDE_WORKBENCH_ALLOWED_ORIGINS}（全大写）在
     * Spring 的宽松绑定里匹配不到 {@code fde.workbench.allowed-origins}——实测（真实进程）它<b>不生效</b>，
     * 而 {@code FDE_DB_PASSWORD} 这类"单个普通键"却能生效。换端口启动 dsh web 时需要一个可靠的口子，
     * 所以像 {@code application.yml} 里其它环境变量一样，用一个显式占位符读取。
     */
    public static final String ALLOWED_ORIGINS_ENV = "FDE_WORKBENCH_ALLOWED_ORIGINS";

    /**
     * 允许跨源访问 {@code /api/**} 的浏览器来源（scheme + host + port，精确匹配，不带尾斜杠）。
     * <p>
     * <b>为什么要有这项配置</b>：dsh web 页面在 {@code http://127.0.0.1:3080}、后端在
     * {@code http://127.0.0.1:8081}，浏览器视为跨源；没有 CORS 白名单时预检直接 403
     * （"Invalid CORS request"），页面所有 {@code /api} 请求都 "Failed to fetch"。
     * <p>
     * <b>为什么不写成 {@code *}</b>：那等于让任意网页带着用户的 JWT 调本接口；
     * 默认只放本机两种写法（{@code 127.0.0.1} 与 {@code localhost} 在浏览器眼里是<b>不同源</b>，
     * 少了任何一个都会有一半场景打不通）。换端口启动 dsh web（如 {@code FDE_DSH_PORT=3081}）时，
     * 由部署方显式追加，不做端口通配。
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://127.0.0.1:3080", "http://localhost:3080"));

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public long getNodeOutputMaxBytes() {
        return nodeOutputMaxBytes;
    }

    public void setNodeOutputMaxBytes(long nodeOutputMaxBytes) {
        this.nodeOutputMaxBytes = nodeOutputMaxBytes;
    }

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 归一化后的允许来源列表：去空白、去尾部斜杠、丢弃空项。
     * <p>
     * 为什么在读取处归一化而不是"配置里写规范就行"：{@code application.yml} 和环境变量两种来源
     * 都容易多写一个尾斜杠（{@code http://127.0.0.1:3080/}），而浏览器的 {@code Origin} 头
     * 永远不带尾斜杠，多一个字符就匹配不上——这种错误只表现为"预检莫名 403"，很难排查。
     *
     * @return 可直接用作 CORS 白名单的来源列表（保持配置顺序）
     */
    public List<String> resolvedAllowedOrigins() {
        if (allowedOrigins == null) {
            return List.of();
        }
        // 环境变量优先：逗号分隔（"http://a:1, http://b:2"），空白项与重复项由下面统一归一化
        String fromEnv = System.getenv(ALLOWED_ORIGINS_ENV);
        List<String> source = fromEnv == null || fromEnv.isBlank()
                ? allowedOrigins
                : List.of(fromEnv.split(","));
        return source.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .map(origin -> origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin)
                .distinct()
                .toList();
    }

    /** 技能包大小限制 */
    public static class Skill {

        /** 单个文件大小上限，默认 20 MiB */
        private long maxFileBytes = 20L * 1024 * 1024;

        /** 解包后总量上限，默认 100 MiB */
        private long maxTotalBytes = 100L * 1024 * 1024;

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public long getMaxTotalBytes() {
            return maxTotalBytes;
        }

        public void setMaxTotalBytes(long maxTotalBytes) {
            this.maxTotalBytes = maxTotalBytes;
        }
    }
}
