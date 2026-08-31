package com.agentflow.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Built-in report_generate implementation using a deterministic Markdown template. */
@Component
public class ReportGenerateToolHandler implements BuiltinToolHandler {
    static final String RESULT_SUMMARY = "已生成 Markdown 处理报告。";

    private final ObjectMapper objectMapper;

    public ReportGenerateToolHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public HandlerResult execute(JsonNode arguments) {
        StringBuilder markdown = new StringBuilder()
                .append("# ")
                .append(arguments.path("title").textValue())
                .append("\n\n## 结论\n")
                .append(arguments.path("summary").textValue());

        JsonNode rootCause = arguments.get("rootCause");
        if (rootCause != null) {
            markdown.append("\n\n## 原因分析\n").append(rootCause.textValue());
        }

        JsonNode suggestions = arguments.get("suggestions");
        if (suggestions != null) {
            markdown.append("\n\n## 处理建议\n");
            for (int index = 0; index < suggestions.size(); index++) {
                if (index > 0) {
                    markdown.append('\n');
                }
                markdown.append(index + 1)
                        .append(". ")
                        .append(suggestions.get(index).textValue());
            }
        }

        return new HandlerResult(
                RESULT_SUMMARY,
                objectMapper.valueToTree(new ReportGenerateToolData(markdown.toString()))
        );
    }
}
