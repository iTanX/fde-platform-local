package com.fde.workbench.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fde.workbench.common.error.ErrorCode;

/**
 * 统一响应体 {@code {code, message, data}}，结构见 docs/api.md §1.2。
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResult<T> {

    /** 业务错误码，0 表示成功 */
    private int code;

    /** 提示文案，成功时为 "ok" */
    private String message;

    /** 业务数据，失败时为 null（仍输出该字段，便于前端统一处理） */
    private T data;

    public ApiResult() {
    }

    public ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功响应。
     *
     * @param data 业务数据
     * @return 统一返回体
     */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(), data);
    }

    /**
     * 成功响应（无数据）。
     */
    public static ApiResult<Void> ok() {
        return ok(null);
    }

    /**
     * 失败响应。
     *
     * @param errorCode 业务错误码
     * @param message   提示文案
     */
    public static <T> ApiResult<T> fail(ErrorCode errorCode, String message) {
        return new ApiResult<>(errorCode.getCode(), message, null);
    }

    /**
     * 失败响应（使用错误码默认文案）。
     */
    public static <T> ApiResult<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getDefaultMessage());
    }

    /** 需要按整数 code 构造失败响应时使用（如未知异常统一 19901） */
    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
