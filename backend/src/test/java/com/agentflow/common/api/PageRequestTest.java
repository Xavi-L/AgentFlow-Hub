package com.agentflow.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PageRequestTest {

    @Test
    void shouldUseDocumentedDefaults() {
        PageRequest request = new PageRequest();

        assertThat(request.getPage()).isEqualTo(1);
        assertThat(request.getPageSize()).isEqualTo(20);
        assertThat(request.offset()).isZero();
    }

    @Test
    void shouldNormalizeValuesAndCalculateOffset() {
        PageRequest request = new PageRequest(-1, 500);

        assertThat(request.getPage()).isEqualTo(1);
        assertThat(request.getPageSize()).isEqualTo(100);

        request.setPage(3);
        request.setPageSize(20);
        assertThat(request.offset()).isEqualTo(40);
    }

    @Test
    void shouldFailExplicitlyWhenOffsetOverflows() {
        PageRequest request = new PageRequest(Integer.MAX_VALUE, 100);

        assertThatThrownBy(request::offset).isInstanceOf(ArithmeticException.class);
    }
}
