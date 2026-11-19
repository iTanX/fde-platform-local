package com.fde.workbench.api.handler;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.SkillReferencedException;
import com.fde.workbench.service.vo.SkillReferencesVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把业务异常、参数校验异常与未知异常统一转换为 {@link ApiResult}，并按错误码设置 HTTP 状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常 → 统一返回体，HTTP 状态码取错误码自带值（如 401 / 403 / 404 / 409）。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBizException(BizException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("业务异常：code={}, message={}", errorCode.getCode(), ex.resolveMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResult.fail(errorCode, ex.resolveMessage()));
    }

    /**
     * 删除技能被引用 → 409/10403，并把引用清单写进 {@code data}（docs/api.md §5）。
     * <p>
     * 必须单独声明：方法参数比 {@link #handleBizException} 更具体，Spring 会优先匹配这里；
     * 否则 data 恒为 null，前端就拿不到"被哪个需求的哪个节点引用"。
     */
    @ExceptionHandler(SkillReferencedException.class)
    public ResponseEntity<ApiResult<SkillReferencesVO>> handleSkillReferenced(SkillReferencedException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("删除技能被引用拒绝：引用数={}", ex.getReferences().references().size());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new ApiResult<>(errorCode.getCode(), ex.resolveMessage(), ex.getReferences()));
    }

    /**
     * 请求体 Jakarta Validation 校验失败 → 400 / 10001，message 汇总各字段的首条错误。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("；"));
        if (detail.isEmpty()) {
            detail = ErrorCode.PARAM_INVALID.getDefaultMessage();
        }
        log.warn("参数校验失败：{}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail(ErrorCode.PARAM_INVALID, detail));
    }

    /**
     * 缺少必填查询参数 → 400 / 10001。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必填参数：" + ex.getParameterName());
    }

    /**
     * 参数类型不匹配（如路径 id 传了非数字）→ 400 / 10001。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数格式不正确：" + ex.getName());
    }

    /**
     * 请求体不是合法 JSON 或字段类型不符 → 400 / 10001（而非 500）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败：{}", ex.getMessage());
        return badRequest("请求体格式不正确，需为合法 JSON");
    }

    /**
     * multipart 缺少 {@code file} 字段 → 400 / 10001。
     * <p>
     * 这是 Servlet 的请求解析异常，不属于 {@code MethodArgumentNotValidException}，
     * 不单独处理会被兜底成 500，前端就无法区分"没选文件"与"服务出错"。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        return badRequest("缺少上传文件字段：" + ex.getRequestPartName());
    }

    /**
     * 上传文件超过容器上限 → 413 / 10501（api.md §1.3）。
     * <p>
     * 容器层的 {@code spring.servlet.multipart.max-request-size} 会在进入 Controller 前抛出本异常；
     * 技能包解包后的单文件 / 总量限制在 {@code SkillPackageExtractor} 里同样是 10501。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过容器上限：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResult.fail(ErrorCode.FILE_TOO_LARGE, "上传文件超出大小限制"));
    }

    /**
     * 访问了不存在的路径 / 静态资源 → 404 / 10301，而不是被兜底成 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.fail(ErrorCode.NOT_FOUND, "接口不存在"));
    }

    /**
     * 其他未预期异常 → 19901，并记录完整堆栈。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnknownException(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(ErrorCode.INTERNAL_ERROR));
    }

    /** 统一的 400 / 10001 构造 */
    private static ResponseEntity<ApiResult<Void>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResult.fail(ErrorCode.PARAM_INVALID, message));
    }
}
