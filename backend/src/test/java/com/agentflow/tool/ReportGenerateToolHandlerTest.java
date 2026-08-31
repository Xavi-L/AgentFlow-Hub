package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReportGenerateToolHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReportGenerateToolHandler handler = new ReportGenerateToolHandler(objectMapper);

    @Test
    void shouldGenerateTheExactCompleteMarkdownTemplate() throws Exception {
        BuiltinToolHandler.HandlerResult result = handler.execute(objectMapper.readTree("""
                {
                  "title":"order_1024 支付失败分析报告",
                  "summary":"订单支付失败。",
                  "rootCause":"支付网关响应超时。",
                  "suggestions":["检查网关状态。","确认订单未重复扣款。"]
                }
                """));

        assertThat(result.summary()).isEqualTo("已生成 Markdown 处理报告。");
        assertThat(result.data().fieldNames()).toIterable().containsExactly("markdown");
        assertThat(result.data().path("markdown").textValue()).isEqualTo("""
                # order_1024 支付失败分析报告

                ## 结论
                订单支付失败。

                ## 原因分析
                支付网关响应超时。

                ## 处理建议
                1. 检查网关状态。
                2. 确认订单未重复扣款。""");
    }

    @Test
    void shouldGenerateOnlyTheRequiredSectionsWhenOptionalFieldsAreAbsent() throws Exception {
        BuiltinToolHandler.HandlerResult result = handler.execute(objectMapper.readTree("""
                {"title":"支付失败报告","summary":"订单支付失败。"}
                """));

        assertThat(result.data().path("markdown").textValue()).isEqualTo("""
                # 支付失败报告

                ## 结论
                订单支付失败。""");
        assertThat(result.data().path("markdown").textValue())
                .doesNotContain("原因分析", "处理建议");
    }

    @Test
    void shouldKeepSuggestionOrderWithoutDeduplicationOrReordering() throws Exception {
        BuiltinToolHandler.HandlerResult result = handler.execute(objectMapper.readTree("""
                {
                  "title":"支付失败报告",
                  "summary":"订单支付失败。",
                  "suggestions":["先检查网关。","再确认扣款。","先检查网关。"]
                }
                """));

        assertThat(result.data().path("markdown").textValue()).isEqualTo("""
                # 支付失败报告

                ## 结论
                订单支付失败。

                ## 处理建议
                1. 先检查网关。
                2. 再确认扣款。
                3. 先检查网关。""");
    }

    @Test
    void shouldAllowRootCauseWithoutSuggestions() throws Exception {
        BuiltinToolHandler.HandlerResult result = handler.execute(objectMapper.readTree("""
                {
                  "title":"支付失败报告",
                  "summary":"订单支付失败。",
                  "rootCause":"支付网关响应超时。"
                }
                """));

        assertThat(result.data().path("markdown").textValue())
                .contains("## 原因分析\n支付网关响应超时。")
                .doesNotContain("## 处理建议");
    }
}
