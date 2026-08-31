package com.agentflow.tool.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.tool.ToolDefinition;
import com.agentflow.tool.ToolDefinitionService;
import com.agentflow.tool.ToolExecutionCommand;
import com.agentflow.tool.ToolExecutionResult;
import com.agentflow.tool.ToolRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ToolControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldListOnlyTheSafeActiveToolProjection() throws Exception {
        ToolDefinitionService definitionService = Mockito.mock(ToolDefinitionService.class);
        ToolRuntime runtime = Mockito.mock(ToolRuntime.class);
        when(definitionService.listActive()).thenReturn(List.of(definition()));

        mockMvc(definitionService, runtime).perform(MockMvcRequestBuilders.get("/api/v1/tools")
                        .header("X-Trace-Id", "af-test-tool-list"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Tools retrieved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.length()")
                        .value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].length()"
                ).value(12))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].id"
                ).value("270000000000000001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].toolCode"
                ).value("order_query"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].permissionLevel"
                ).value("MEDIUM"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].retryCount"
                ).value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].inputSchema.required[0]"
                ).value("orderNo"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].config"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].handler"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].createdAt"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[0].deletedAt"
                ).doesNotExist());

        verify(definitionService).listActive();
        verifyNoInteractions(runtime);
    }

    @Test
    void shouldSendStandaloneExecutionThroughToolRuntimeAndReturnTheSafeOrderData() throws Exception {
        ToolDefinitionService definitionService = Mockito.mock(ToolDefinitionService.class);
        ToolRuntime runtime = Mockito.mock(ToolRuntime.class);
        ToolExecutionResult result = ToolExecutionResult.success(
                "order_query",
                "safe summary",
                objectMapper.readTree("""
                        {
                          "orderNo":"order_1024",
                          "amount":199.00,
                          "currency":"CNY",
                          "status":"CREATED",
                          "paymentStatus":"PAY_FAILED",
                          "errorCode":"E_PAY_TIMEOUT"
                        }
                        """),
                9
        );
        when(runtime.execute(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        mockMvc(definitionService, runtime).perform(MockMvcRequestBuilders.post(
                        "/api/v1/tools/{toolId}/test",
                        270000000000000001L
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":{\"orderNo\":\"order_1024\"}}")
                        .header("X-Trace-Id", "af-test-tool-execute"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Tool executed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.success"
                ).value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.toolCode"
                ).value("order_query"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.length()"
                ).value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.amount"
                ).value(199.0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.paymentStatus"
                ).value("PAY_FAILED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.userNo"
                ).doesNotExist());

        ArgumentCaptor<ToolExecutionCommand> captor = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(runtime).execute(captor.capture());
        ToolExecutionCommand command = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(command.toolId()).isEqualTo(270000000000000001L);
        org.assertj.core.api.Assertions.assertThat(command.taskId()).isNull();
        org.assertj.core.api.Assertions.assertThat(command.stepId()).isNull();
        org.assertj.core.api.Assertions.assertThat(command.arguments().path("orderNo").textValue())
                .isEqualTo("order_1024");
        verifyNoInteractions(definitionService);
    }

    @Test
    void shouldMapRuntimeArgumentRejectionToTheDedicated400() throws Exception {
        ToolDefinitionService definitionService = Mockito.mock(ToolDefinitionService.class);
        ToolRuntime runtime = Mockito.mock(ToolRuntime.class);
        when(runtime.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException(ErrorCode.TOOL_ARGUMENT_INVALID));

        mockMvc(definitionService, runtime).perform(MockMvcRequestBuilders.post(
                        "/api/v1/tools/{toolId}/test",
                        270000000000000001L
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":{}}")
                        .header("X-Trace-Id", "af-test-tool-rejected"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("TOOL_ARGUMENT_INVALID"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Tool arguments are invalid"));

        verify(runtime).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectMalformedJsonBeforeToolRuntimeSoNoCallLogCanBeInvented() throws Exception {
        ToolDefinitionService definitionService = Mockito.mock(ToolDefinitionService.class);
        ToolRuntime runtime = Mockito.mock(ToolRuntime.class);

        mockMvc(definitionService, runtime).perform(MockMvcRequestBuilders.post(
                        "/api/v1/tools/{toolId}/test",
                        270000000000000001L
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":")
                        .header("X-Trace-Id", "af-test-tool-malformed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_REQUEST_BODY_INVALID"));

        verifyNoInteractions(runtime, definitionService);
    }

    private MockMvc mockMvc(ToolDefinitionService definitionService, ToolRuntime runtime) {
        ObjectMapper mvcObjectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new ToolController(definitionService, runtime))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mvcObjectMapper))
                .build();
    }

    private ToolDefinition definition() throws Exception {
        return new ToolDefinition(
                270000000000000001L,
                "order_query",
                "Order Query",
                "description",
                "BUILTIN",
                objectMapper.readTree("""
                        {
                          "type":"object",
                          "properties":{"orderNo":{"type":"string","minLength":1,"maxLength":64}},
                          "required":["orderNo"],
                          "additionalProperties":false
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "type":"object",
                          "properties":{
                            "orderNo":{"type":"string"},
                            "amount":{"type":"number"},
                            "currency":{"type":"string"},
                            "status":{"type":"string"},
                            "paymentStatus":{"type":"string"},
                            "errorCode":{"type":["string","null"]}
                          }
                        }
                        """),
                objectMapper.readTree("{\"handler\":\"must-not-leak\",\"readonly\":true}"),
                3000,
                0,
                false,
                "MEDIUM",
                "ACTIVE"
        );
    }
}
