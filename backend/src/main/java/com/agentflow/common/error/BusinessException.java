package com.agentflow.common.error;

import java.util.Objects;

/**
 * 中文：业务流程可预期失败时主动抛出的运行时异常，例如资源不存在或状态不允许。
 * 它把“稳定的错误分类”保存在 {@link ErrorCode}，把“本次失败的具体说明”交给
 * {@link RuntimeException#getMessage()} 保存。
 *
 * <p>English: An unchecked exception for expected business failures, such as a missing
 * resource or an invalid state transition. The stable classification lives in
 * {@link ErrorCode}, while the occurrence-specific explanation is stored by
 * {@link RuntimeException#getMessage()}.
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    /**
     * 中文：未提供具体说明时使用错误码的默认文案。
     * English: Uses the error code's default message when no specific detail is supplied.
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, defaultMessage(errorCode));
    }

    /**
     * 中文：{@code super(message)} 必须先初始化父类中的异常消息；错误码本身保持不可变。
     * English: {@code super(message)} initializes the parent exception message first;
     * the error-code definition itself remains immutable.
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    private static String defaultMessage(ErrorCode errorCode) {
        return Objects.requireNonNull(errorCode, "errorCode must not be null").getMessage();
    }
}
