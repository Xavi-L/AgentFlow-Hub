package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.tool.model.ToolDefinitionRow;
import com.agentflow.tool.repository.ToolDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ToolDefinitionServiceTest {

    @Test
    void shouldParsePersistedSchemasAndKeepInternalConfigInsideTheRuntimeDefinition() {
        ToolDefinitionMapper mapper = Mockito.mock(ToolDefinitionMapper.class);
        ToolDefinitionRow row = orderRow();
        when(mapper.selectActiveById(270000000000000001L)).thenReturn(row);
        ToolDefinitionService service = new ToolDefinitionService(mapper, new ObjectMapper());

        ToolDefinition definition = service.findActiveById(270000000000000001L).orElseThrow();

        assertThat(definition.toolCode()).isEqualTo("order_query");
        assertThat(definition.inputSchema().path("required").get(0).textValue()).isEqualTo("orderNo");
        assertThat(definition.outputSchema().path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "orderNo",
                        "amount",
                        "currency",
                        "status",
                        "paymentStatus",
                        "errorCode"
                );
        assertThat(definition.config().path("handler").textValue()).isEqualTo("orderQueryTool");
        assertThat(definition.permissionLevel()).isEqualTo("MEDIUM");
        assertThat(definition.retryCount()).isZero();
        verify(mapper).selectActiveById(270000000000000001L);
    }

    @Test
    void shouldReturnTheDatabaseOrderedActiveListWithoutOwnerInput() {
        ToolDefinitionMapper mapper = Mockito.mock(ToolDefinitionMapper.class);
        when(mapper.selectAllActive()).thenReturn(List.of(orderRow(), paymentRow(), reportRow()));
        ToolDefinitionService service = new ToolDefinitionService(mapper, new ObjectMapper());

        List<ToolDefinition> definitions = service.listActive();

        assertThat(definitions).extracting(ToolDefinition::toolCode)
                .containsExactly("order_query", "payment_log_query", "report_generate");
        ToolDefinition paymentDefinition = definitions.get(1);
        assertThat(paymentDefinition.id()).isEqualTo(280000000000000001L);
        assertThat(paymentDefinition.inputSchema().path("anyOf")).hasSize(2);
        assertThat(paymentDefinition.config().path("handler").textValue())
                .isEqualTo("paymentLogQueryTool");
        assertThat(paymentDefinition.timeoutMs()).isEqualTo(5000);
        assertThat(paymentDefinition.permissionLevel()).isEqualTo("MEDIUM");
        ToolDefinition reportDefinition = definitions.get(2);
        assertThat(reportDefinition.id()).isEqualTo(290000000000000001L);
        assertThat(reportDefinition.inputSchema().path("required").get(0).textValue())
                .isEqualTo("title");
        assertThat(reportDefinition.inputSchema().path("required").get(1).textValue())
                .isEqualTo("summary");
        assertThat(reportDefinition.inputSchema().path("properties").path("suggestions")
                .path("items").path("type").textValue()).isEqualTo("string");
        assertThat(reportDefinition.outputSchema().path("properties").fieldNames()).toIterable()
                .containsExactly("markdown");
        assertThat(reportDefinition.config().path("handler").textValue())
                .isEqualTo("reportGenerateTool");
        assertThat(reportDefinition.timeoutMs()).isEqualTo(10000);
        assertThat(reportDefinition.retryCount()).isZero();
        assertThat(reportDefinition.permissionLevel()).isEqualTo("LOW");
        verify(mapper).selectAllActive();
    }

    private static ToolDefinitionRow orderRow() {
        ToolDefinitionRow row = new ToolDefinitionRow();
        row.setId(270000000000000001L);
        row.setToolCode("order_query");
        row.setName("Order Query");
        row.setDescription("description");
        row.setType("BUILTIN");
        row.setInputSchemaJson("""
                {
                  "type":"object",
                  "properties":{"orderNo":{"type":"string","minLength":1,"maxLength":64}},
                  "required":["orderNo"],
                  "additionalProperties":false
                }
                """);
        row.setOutputSchemaJson("""
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
                """);
        row.setConfigJson("{\"handler\":\"orderQueryTool\",\"readonly\":true}");
        row.setTimeoutMs(3000);
        row.setRetryCount(0);
        row.setRequiresConfirmation(false);
        row.setPermissionLevel("MEDIUM");
        row.setStatus("ACTIVE");
        return row;
    }

    private static ToolDefinitionRow paymentRow() {
        ToolDefinitionRow row = new ToolDefinitionRow();
        row.setId(280000000000000001L);
        row.setToolCode("payment_log_query");
        row.setName("Payment Log Query");
        row.setDescription("description");
        row.setType("BUILTIN");
        row.setInputSchemaJson("""
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
                """);
        row.setOutputSchemaJson("""
                {
                  "type":"object",
                  "properties":{"logs":{"type":"array"}}
                }
                """);
        row.setConfigJson("{\"handler\":\"paymentLogQueryTool\",\"readonly\":true}");
        row.setTimeoutMs(5000);
        row.setRetryCount(0);
        row.setRequiresConfirmation(false);
        row.setPermissionLevel("MEDIUM");
        row.setStatus("ACTIVE");
        return row;
    }

    private static ToolDefinitionRow reportRow() {
        ToolDefinitionRow row = new ToolDefinitionRow();
        row.setId(290000000000000001L);
        row.setToolCode("report_generate");
        row.setName("Report Generate");
        row.setDescription("description");
        row.setType("BUILTIN");
        row.setInputSchemaJson("""
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
                """);
        row.setOutputSchemaJson("""
                {
                  "type":"object",
                  "properties":{"markdown":{"type":"string"}},
                  "required":["markdown"],
                  "additionalProperties":false
                }
                """);
        row.setConfigJson("{\"handler\":\"reportGenerateTool\",\"readonly\":true}");
        row.setTimeoutMs(10000);
        row.setRetryCount(0);
        row.setRequiresConfirmation(false);
        row.setPermissionLevel("LOW");
        row.setStatus("ACTIVE");
        return row;
    }
}
