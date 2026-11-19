package com.fde.workbench.common.error;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业务错误码枚举，取值与 docs/api.md §1.3 错误码表一一对应（HTTP 状态码 + 业务 code）。
 * <p>
 * 约定：{@link #SUCCESS} 表示成功（code=0，HTTP 200）；其余每个常量同时携带业务 code 与对应 HTTP 状态码，
 * 由全局异常处理统一转换为 HTTP 响应状态。
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, 200, "ok"),

    /** 参数校验失败 */
    PARAM_INVALID(10001, 400, "参数校验失败"),

    /** 未登录或 token 过期 */
    UNAUTHORIZED(10101, 401, "未登录或 token 过期"),

    /** 用户名或密码错误 */
    BAD_CREDENTIALS(10102, 401, "用户名或密码错误"),

    /** 不是项目成员 */
    NOT_PROJECT_MEMBER(10201, 403, "不是项目成员"),

    /** 无操作权限（非 owner / 非数据创建者） */
    FORBIDDEN(10202, 403, "无操作权限"),

    /** 资源不存在 */
    NOT_FOUND(10301, 404, "资源不存在"),

    /** 名称重复 */
    DUPLICATE_NAME(10401, 409, "名称重复"),

    /** 乐观锁冲突（revision 不一致） */
    REVISION_CONFLICT(10402, 409, "数据已被修改，请刷新后重试"),

    /** 被引用，不能删除 */
    REFERENCED(10403, 409, "被引用，不能删除"),

    /** 状态不允许该操作 */
    STATE_NOT_ALLOWED(10404, 409, "当前状态不允许该操作"),

    /** 文件超出大小限制 */
    FILE_TOO_LARGE(10501, 413, "文件超出大小限制"),

    /** 文件类型不允许 */
    UNSUPPORTED_MEDIA_TYPE(10502, 415, "文件类型不允许"),

    /** 服务内部错误 */
    INTERNAL_ERROR(19901, 500, "服务内部错误"),

    /** 下游不可用（DSH 或存储） */
    DOWNSTREAM_UNAVAILABLE(19902, 502, "下游服务不可用");

    /** 业务错误码（对应 api.md §1.3 的 code 列） */
    private final int code;

    /** 对应的 HTTP 状态码 */
    private final int httpStatus;

    /** 默认中文提示，可被 BizException 的自定义 message 覆盖 */
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /**
     * 业务错误码；序列化为 JSON 数字而非枚举名。
     */
    @JsonValue
    public int getCode() {
        return code;
    }

    /** 对应 HTTP 状态码 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** 默认提示文案 */
    public String getDefaultMessage() {
        return defaultMessage;
    }

    /** 是否表示成功 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 按业务 code 反查枚举，未匹配时返回 {@link #INTERNAL_ERROR}。
     *
     * @param code 业务错误码
     * @return 对应枚举
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return INTERNAL_ERROR;
    }
}
