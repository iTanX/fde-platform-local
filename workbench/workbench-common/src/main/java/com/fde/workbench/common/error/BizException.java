package com.fde.workbench.common.error;

/**
 * 业务异常：携带 {@link ErrorCode}，由全局异常处理转换为统一返回体与对应 HTTP 状态码。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码 */
    private final ErrorCode errorCode;

    /**
     * 使用错误码的默认提示文案构造。
     *
     * @param errorCode 业务错误码
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用自定义提示文案构造（例如"用户不存在，请先注册"）。
     *
     * @param errorCode 业务错误码
     * @param message   自定义提示文案
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 业务错误码 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 对外的提示文案：优先使用自定义 message */
    public String resolveMessage() {
        String message = getMessage();
        return (message == null || message.isBlank()) ? errorCode.getDefaultMessage() : message;
    }

    /** 便于业务层直接抛出的快捷方法 */
    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    /** 便于业务层直接抛出的快捷方法（自定义文案） */
    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }
}
