package com.agentflow.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void shouldUseDefaultOrCustomMessageWithoutMutatingErrorCode() {
        BusinessException defaultException = new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        BusinessException customException = new BusinessException(
                ErrorCode.COMMON_NOT_FOUND,
                "Knowledge base not found"
        );

        assertThat(defaultException.getMessage()).isEqualTo("Resource not found");
        assertThat(customException.getMessage()).isEqualTo("Knowledge base not found");
        assertThat(customException.getErrorCode()).isEqualTo(ErrorCode.COMMON_NOT_FOUND);
        assertThat(ErrorCode.COMMON_NOT_FOUND.getMessage()).isEqualTo("Resource not found");
    }
}
