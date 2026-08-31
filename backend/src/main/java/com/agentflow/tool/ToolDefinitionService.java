package com.agentflow.tool;

import com.agentflow.tool.model.ToolDefinitionRow;
import com.agentflow.tool.repository.ToolDefinitionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads the global, visible tool registry from PostgreSQL. */
@Service
public class ToolDefinitionService {
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ObjectMapper objectMapper;

    public ToolDefinitionService(ToolDefinitionMapper toolDefinitionMapper, ObjectMapper objectMapper) {
        this.toolDefinitionMapper = Objects.requireNonNull(
                toolDefinitionMapper,
                "toolDefinitionMapper must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Transactional(readOnly = true)
    public Optional<ToolDefinition> findActiveById(Long toolId) {
        Objects.requireNonNull(toolId, "toolId must not be null");
        return Optional.ofNullable(toolDefinitionMapper.selectActiveById(toolId)).map(this::toDefinition);
    }

    @Transactional(readOnly = true)
    public List<ToolDefinition> listActive() {
        return toolDefinitionMapper.selectAllActive().stream().map(this::toDefinition).toList();
    }

    private ToolDefinition toDefinition(ToolDefinitionRow row) {
        return new ToolDefinition(
                Objects.requireNonNull(row.getId(), "persisted tool id must not be null"),
                Objects.requireNonNull(row.getToolCode(), "persisted tool code must not be null"),
                Objects.requireNonNull(row.getName(), "persisted tool name must not be null"),
                Objects.requireNonNull(row.getDescription(), "persisted tool description must not be null"),
                Objects.requireNonNull(row.getType(), "persisted tool type must not be null"),
                parseJson(row.getInputSchemaJson(), "input_schema"),
                parseJson(row.getOutputSchemaJson(), "output_schema"),
                parseJson(row.getConfigJson(), "config"),
                Objects.requireNonNull(row.getTimeoutMs(), "persisted timeout_ms must not be null"),
                Objects.requireNonNull(row.getRetryCount(), "persisted retry_count must not be null"),
                Objects.requireNonNull(
                        row.getRequiresConfirmation(),
                        "persisted requires_confirmation must not be null"
                ),
                Objects.requireNonNull(
                        row.getPermissionLevel(),
                        "persisted permission_level must not be null"
                ),
                Objects.requireNonNull(row.getStatus(), "persisted tool status must not be null")
        );
    }

    private JsonNode parseJson(String json, String column) {
        try {
            return objectMapper.readTree(Objects.requireNonNull(json, column + " must not be null"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Persisted " + column + " is not valid JSON", ex);
        }
    }
}
