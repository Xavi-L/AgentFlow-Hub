package com.agentflow.common.api;

import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.web.TraceIdHolder;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 中文：所有 REST 接口共用的响应外壳。泛型 {@code T} 表示每个接口自己的数据类型，
 * 因此可以在保持 JSON 外层结构一致的同时保留编译期类型检查。
 *
 * <p>English: The shared response envelope for every REST endpoint. The generic type
 * {@code T} represents endpoint-specific data, keeping the outer JSON contract stable
 * without giving up compile-time type safety.
 *
 * @param <T> 中文：响应数据类型；English: the response payload type
 */
public class ApiResponse<T> {
    private final String code;
    private final String message;
    // 中文：失败响应也要显式输出 data: null，保持前端解包结构稳定。
    // English: Failure responses explicitly retain data: null for a stable client contract.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final T data;
    private final String traceId;
    private final OffsetDateTime timestamp;

    /**
     * 中文：构造器保持私有，强制调用者通过语义明确的 success/fail 工厂方法创建响应。
     * English: The constructor is private so callers must use the intention-revealing
     * success/fail factory methods.
     */
    private ApiResponse(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceIdHolder.getTraceId();
        this.timestamp = OffsetDateTime.now();
    }

    /**
     * 中文：使用系统默认成功文案创建成功响应。
     * English: Creates a successful response with the system's default success message.
     */
    public static <T> ApiResponse<T> success(T data) {
        return success(ErrorCode.OK.getMessage(), data);
    }

    /**
     * 中文：使用调用方给出的业务文案创建成功响应。
     * English: Creates a successful response with a caller-provided business message.
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(ErrorCode.OK.getCode(), message, data);
    }

    /**
     * 中文：用原始错误码和错误文案创建失败响应；适合尚未进入 ErrorCode 枚举的兼容场景。
     * English: Creates a failure response from a raw code and message, mainly for
     * compatibility cases not yet represented by the ErrorCode enum.
     */
    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(
                Objects.requireNonNull(code, "code must not be null"),
                Objects.requireNonNull(message, "message must not be null"),
                null
        );
    }

    /**
     * 中文：使用错误码预设的默认文案创建失败响应。
     * English: Creates a failure response using the error code's default message.
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return fail(errorCode, errorCode.getMessage());
    }

    /**
     * 中文：保留错误分类，但允许当前异常提供更具体、可安全展示的文案。
     * English: Preserves the error classification while allowing the current failure
     * to provide a more specific, client-safe message.
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return fail(errorCode.getCode(), message);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }
}
