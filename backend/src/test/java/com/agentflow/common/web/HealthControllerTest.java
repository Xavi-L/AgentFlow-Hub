package com.agentflow.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {

    @Test
    void shouldReturnUnifiedResponseWithTheSameTraceIdInHeaderAndBody() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "agentflow-hub");
        environment.setActiveProfiles("test");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController(environment))
                .addFilters(new TraceIdFilter())
                .build();

        mockMvc.perform(get("/api/v1/health")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "af-manual-test-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "af-manual-test-001"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.application").value("agentflow-hub"))
                .andExpect(jsonPath("$.data.profile").value("test"))
                .andExpect(jsonPath("$.traceId").value("af-manual-test-001"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        assertThat(TraceIdHolder.getTraceId()).isNull();
    }
}
