package com.agentflow.common.error;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.web.TraceIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 中文：把 Controller 调用链中的异常统一翻译为稳定的 HTTP + JSON 契约。
 * Controller 因而只关注正常业务流程，不需要重复编写 try/catch。
 *
 * <p>English: Translates exceptions from controller call chains into a stable HTTP and
 * JSON contract. Controllers can therefore focus on the happy-path business flow
 * instead of repeating try/catch blocks.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 中文：业务异常是已知、可安全展示的失败，保留其错误码和具体文案。
     * English: Business exceptions are expected and client-safe, so their code and
     * occurrence-specific message are preserved.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("Business request failed: traceId={}, code={}, message={}",
                TraceIdHolder.getTraceId(), errorCode.getCode(), ex.getMessage());
        return buildResponse(errorCode, ex.getMessage());
    }

    /**
     * 中文：Bean Validation 失败时返回第一个字段错误，便于调用者直接定位输入问题。
     * English: For Bean Validation failures, returns the first field error so API clients
     * can locate the invalid input directly.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException ex
    ) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionHandler::formatFieldError)
                .orElse(ErrorCode.COMMON_PARAM_INVALID.getMessage());
        return buildResponse(ErrorCode.COMMON_PARAM_INVALID, message);
    }

    /**
     * 中文：请求体无法反序列化时，例如 JSON 格式损坏或字段类型错误，不能把它伪装成 500。
     * English: Malformed JSON or incompatible field types are client request errors,
     * not server failures.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequestBody(
            HttpMessageNotReadableException ex
    ) {
        return buildResponse(
                ErrorCode.COMMON_REQUEST_BODY_INVALID,
                ErrorCode.COMMON_REQUEST_BODY_INVALID.getMessage()
        );
    }

    /**
     * 中文：Service 的预检查提升错误信息可读性，但并发请求仍可能同时通过预检查。
     * 数据库唯一约束是最终防线；发生竞争时统一转换为 409，而不是泄露底层 SQL 异常。
     * English: Service pre-checks improve error messages, but concurrent requests can
     * still race. The database unique constraint is the final guard and becomes 409.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("Duplicate database key: traceId={}", TraceIdHolder.getTraceId());
        return buildResponse(
                ErrorCode.USER_ACCOUNT_ALREADY_EXISTS,
                ErrorCode.USER_ACCOUNT_ALREADY_EXISTS.getMessage()
        );
    }

    /**
     * 中文：兜底处理未知异常。服务端记录完整堆栈，但客户端只看到通用文案，避免泄露内部实现。
     * English: Handles unexpected failures as a safety net. The server logs the full
     * stack trace while clients receive only a generic message to avoid leaking internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("Unhandled request failure: traceId={}", TraceIdHolder.getTraceId(), ex);
        return buildResponse(ErrorCode.SYS_INTERNAL_ERROR, ErrorCode.SYS_INTERNAL_ERROR.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> buildResponse(ErrorCode errorCode, String message) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode, message));
    }

    private static String formatFieldError(FieldError fieldError) {
        String detail = fieldError.getDefaultMessage();
        return fieldError.getField() + ": "
                + (detail == null ? ErrorCode.COMMON_PARAM_INVALID.getMessage() : detail);
    }
}
