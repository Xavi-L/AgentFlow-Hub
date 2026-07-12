package com.agentflow.common.error;

/**
 * 中文：系统通用错误码的固定词汇表。枚举项同时绑定业务 code、默认文案和 HTTP 状态码，
 * 避免各 Controller 自行拼装不一致的错误响应。
 *
 * <p>English: The fixed vocabulary of common system errors. Each enum constant binds a
 * business code, default message, and HTTP status so controllers cannot invent
 * inconsistent error responses.
 */
public enum ErrorCode {
    OK("OK", "success", 200),
    COMMON_PARAM_INVALID("COMMON_PARAM_INVALID", "Request parameter is invalid", 400),
    COMMON_REQUEST_BODY_INVALID("COMMON_REQUEST_BODY_INVALID", "Request body is invalid", 400),
    COMMON_NOT_FOUND("COMMON_NOT_FOUND", "Resource not found", 404),
    SYS_INTERNAL_ERROR("SYS_INTERNAL_ERROR", "Internal server error", 500);

    private final String code;
    private final String message;
    private final int httpStatus;

    ErrorCode(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
