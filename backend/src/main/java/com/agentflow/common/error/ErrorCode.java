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
    AUTH_UNAUTHENTICATED("AUTH_UNAUTHENTICATED", "Authentication is required", 401),
    AUTH_INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", "Username or password is incorrect", 401),
    AUTH_ACCOUNT_DISABLED("AUTH_ACCOUNT_DISABLED", "User account is disabled", 403),
    AUTH_TOKEN_INVALID("AUTH_TOKEN_INVALID", "Access token is invalid or expired", 401),
    AUTH_ACCESS_DENIED("AUTH_ACCESS_DENIED", "Access is denied", 403),
    USER_USERNAME_ALREADY_EXISTS("USER_USERNAME_ALREADY_EXISTS", "Username is already in use", 409),
    USER_EMAIL_ALREADY_EXISTS("USER_EMAIL_ALREADY_EXISTS", "Email is already in use", 409),
    USER_ACCOUNT_ALREADY_EXISTS("USER_ACCOUNT_ALREADY_EXISTS", "Username or email is already in use", 409),
    KNOWLEDGE_BASE_NOT_ACTIVE("KNOWLEDGE_BASE_NOT_ACTIVE", "Knowledge base is not active", 409),
    KNOWLEDGE_DOCUMENT_FILE_REQUIRED("KNOWLEDGE_DOCUMENT_FILE_REQUIRED", "A document file is required", 400),
    KNOWLEDGE_DOCUMENT_FILE_EMPTY("KNOWLEDGE_DOCUMENT_FILE_EMPTY", "Document file must not be empty", 400),
    KNOWLEDGE_DOCUMENT_FILE_NAME_INVALID("KNOWLEDGE_DOCUMENT_FILE_NAME_INVALID", "Document file name is invalid", 400),
    KNOWLEDGE_DOCUMENT_FILE_TYPE_UNSUPPORTED(
            "KNOWLEDGE_DOCUMENT_FILE_TYPE_UNSUPPORTED",
            "Only .txt and .md documents are supported",
            400
    ),
    KNOWLEDGE_DOCUMENT_FILE_TOO_LARGE(
            "KNOWLEDGE_DOCUMENT_FILE_TOO_LARGE",
            "Document file exceeds the maximum allowed size",
            413
    ),
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
