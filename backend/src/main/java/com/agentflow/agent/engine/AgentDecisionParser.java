package com.agentflow.agent.engine;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Strict whole-response JSON parser for V36's two-action model protocol. */
@Component
public final class AgentDecisionParser {
    private static final Set<String> TOOL_CALL_FIELDS = Set.of(
            "type", "toolCode", "arguments", "reason"
    );
    private static final Set<String> FINAL_ANSWER_FIELDS = Set.of("type", "answerDraft");

    private final ObjectMapper objectMapper;

    public AgentDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public AgentDecision parse(String modelContent, List<AgentToolSpec> availableTools) {
        if (modelContent == null || modelContent.isBlank() || availableTools == null) {
            throw invalidDecision();
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(modelContent)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalidDecision();
            }

            String type = requiredText(root, "type");
            return switch (type) {
                case "TOOL_CALL" -> parseToolCall(root, availableTools);
                case "FINAL_ANSWER" -> parseFinalAnswer(root);
                default -> throw invalidDecision();
            };
        } catch (AgentExecutionException failure) {
            throw failure;
        } catch (IOException | RuntimeException ignored) {
            throw invalidDecision();
        }
    }

    private static ToolCallDecision parseToolCall(
            JsonNode root,
            List<AgentToolSpec> availableTools
    ) {
        requireExactFields(root, TOOL_CALL_FIELDS);
        String toolCode = requiredText(root, "toolCode");
        JsonNode arguments = root.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            throw invalidDecision();
        }
        String reason = requiredText(root, "reason");

        Map<String, AgentToolSpec> toolsByCode = new HashMap<>();
        for (AgentToolSpec tool : availableTools) {
            if (tool == null || toolsByCode.putIfAbsent(tool.toolCode(), tool) != null) {
                throw invalidDecision();
            }
        }
        AgentToolSpec selected = toolsByCode.get(toolCode);
        if (selected == null) {
            throw invalidDecision();
        }
        return new ToolCallDecision(selected.toolId(), selected.toolCode(), arguments, reason);
    }

    private static FinalAnswerDecision parseFinalAnswer(JsonNode root) {
        requireExactFields(root, FINAL_ANSWER_FIELDS);
        return new FinalAnswerDecision(requiredText(root, "answerDraft"));
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidDecision();
        }
        return value.textValue();
    }

    private static void requireExactFields(JsonNode root, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalidDecision();
        }
    }

    private static AgentExecutionException invalidDecision() {
        return new AgentExecutionException(
                AgentFailureType.INVALID_DECISION,
                "Model decision is invalid"
        );
    }
}
