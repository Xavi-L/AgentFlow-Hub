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
        when(definitionService.listActive()).thenReturn(List.of(
                definition(),
                paymentDefinition(),
                reportDefinition()
        ));

        mockMvc(definitionService, runtime).perform(MockMvcRequestBuilders.get("/api/v1/tools")
                        .header("X-Trace-Id", "af-test-tool-list"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Tools retrieved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.length()")
                        .value(3))
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
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[1].id"
                ).value("280000000000000001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[1].toolCode"
                ).value("payment_log_query"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[1].timeoutMs"
                ).value(5000))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[1].permissionLevel"
                ).value("MEDIUM"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[1].inputSchema.anyOf.length()"
                ).value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[1].config"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].length()"
                ).value(12))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].id"
                ).value("290000000000000001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].toolCode"
                ).value("report_generate"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].timeoutMs"
                ).value(10000))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].permissionLevel"
                ).value("LOW"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].inputSchema.properties.suggestions.minItems"
                ).value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].inputSchema.properties.suggestions.maxItems"
                ).value(20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].inputSchema.properties.suggestions.items.type"
                ).value("string"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].outputSchema.properties.markdown.type"
                ).value("string"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data[2].config"
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
    void shouldReturnTheSafePaymentLogToolDataFromTheSharedRuntimeEndpoint() throws Exception {
        ToolDefinitionService definitionService = Mockito.mock(ToolDefinitionService.class);
        ToolRuntime runtime = Mockito.mock(ToolRuntime.class);
        ToolExecutionResult result = ToolExecutionResult.success(
                "payment_log_query",
                "Found 1 payment log matching the supplied filters.",
                objectMapper.readTree("""
                        {
                          "logs":[{
                            "orderNo":"order_1024",
                            "traceId":"pay-trace-1024",
                            "level":"ERROR",
                            "errorCode":"E_PAY_TIMEOUT",
                            "message":"Payment gateway response timeout after 3000ms",
                            "occurredAt":"2026-05-01T12:00:00+08:00"
                          }]
                        }
                        """),
                7
        );
        when(runtime.execute(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        mockMvc(definitionService, runtime).perform(MockMvcRequestBuilders.post(
                        "/api/v1/tools/{toolId}/test",
                        280000000000000001L
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arguments\":{\"errorCode\":\"E_PAY_TIMEOUT\"}}")
                        .header("X-Trace-Id", "af-test-payment-log-execute"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.toolCode"
                ).value("payment_log_query"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.logs.length()"
                ).value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.logs[0].length()"
                ).value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.logs[0].orderNo"
                ).value("order_1024"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.logs[0].id"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.logs[0].createdAt"
                ).doesNotExist());

        ArgumentCaptor<ToolExecutionCommand> captor = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(runtime).execute(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().toolId())
                .isEqualTo(280000000000000001L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().taskId()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().stepId()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().arguments().path("errorCode").textValue())
                .isEqualTo("E_PAY_TIMEOUT");
        verifyNoInteractions(definitionService);
    }

    @Test
    void shouldReturnOnlyTheDeterministicMarkdownFromTheSharedRuntimeEndpoint() throws Exception {
        ToolDefinitionService definitionService = Mockito.mock(ToolDefinitionService.class);
        ToolRuntime runtime = Mockito.mock(ToolRuntime.class);
        String markdown = """
                # order_1024 支付失败分析报告

                ## 结论
                订单支付失败。

                ## 原因分析
                支付网关响应超时。

                ## 处理建议
                1. 检查网关状态。
                2. 确认订单未重复扣款。""";
        ToolExecutionResult result = ToolExecutionResult.success(
                "report_generate",
                "已生成 Markdown 处理报告。",
                objectMapper.createObjectNode().put("markdown", markdown),
                3
        );
        when(runtime.execute(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        mockMvc(definitionService, runtime).perform(MockMvcRequestBuilders.post(
                        "/api/v1/tools/{toolId}/test",
                        290000000000000001L
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "arguments":{
                                    "title":"order_1024 支付失败分析报告",
                                    "summary":"订单支付失败。",
                                    "rootCause":"支付网关响应超时。",
                                    "suggestions":["检查网关状态。","确认订单未重复扣款。"]
                                  }
                                }
                                """)
                        .header("X-Trace-Id", "af-test-report-generate-execute"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.toolCode"
                ).value("report_generate"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.summary"
                ).value("已生成 Markdown 处理报告。"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.length()"
                ).value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.markdown"
                ).value(markdown))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.data.title"
                ).doesNotExist());

        ArgumentCaptor<ToolExecutionCommand> captor = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(runtime).execute(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().toolId())
                .isEqualTo(290000000000000001L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().taskId()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().stepId()).isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().arguments().path("suggestions").get(0)
                .textValue()).isEqualTo("检查网关状态。");
        verifyNoInteractions(definitionService);
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

    private ToolDefinition paymentDefinition() throws Exception {
        return new ToolDefinition(
                280000000000000001L,
                "payment_log_query",
                "Payment Log Query",
                "description",
                "BUILTIN",
                objectMapper.readTree("""
                        {
                          "type":"object",
                          "properties":{
                            "orderNo":{"type":"string","minLength":1,"maxLength":64},
                            "errorCode":{"type":"string","minLength":1,"maxLength":64},
                            "limit":{"type":"integer","minimum":1,"maximum":20,"default":10}
                          },
                          "anyOf":[
                            {"required":["orderNo"]},
                            {"required":["errorCode"]}
                          ],
                          "additionalProperties":false
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "type":"object",
                          "properties":{"logs":{"type":"array"}}
                        }
                        """),
                objectMapper.readTree("{\"handler\":\"must-not-leak\",\"readonly\":true}"),
                5000,
                0,
                false,
                "MEDIUM",
                "ACTIVE"
        );
    }

    private ToolDefinition reportDefinition() throws Exception {
        return new ToolDefinition(
                290000000000000001L,
                "report_generate",
                "Report Generate",
                "description",
                "BUILTIN",
                objectMapper.readTree("""
                        {
                          "type":"object",
                          "properties":{
                            "title":{"type":"string","minLength":1,"maxLength":255},
                            "summary":{"type":"string","minLength":1,"maxLength":4000},
                            "rootCause":{"type":"string","minLength":1,"maxLength":4000},
                            "suggestions":{
                              "type":"array",
                              "minItems":1,
                              "maxItems":20,
                              "items":{"type":"string","minLength":1,"maxLength":1000}
                            }
                          },
                          "required":["title","summary"],
                          "additionalProperties":false
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "type":"object",
                          "properties":{"markdown":{"type":"string"}},
                          "required":["markdown"],
                          "additionalProperties":false
                        }
                        """),
                objectMapper.readTree("{\"handler\":\"must-not-leak\",\"readonly\":true}"),
                10000,
                0,
                false,
                "LOW",
                "ACTIVE"
        );
    }
}
