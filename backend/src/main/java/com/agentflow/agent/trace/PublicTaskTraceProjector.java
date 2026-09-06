package com.agentflow.agent.trace;

import com.agentflow.agent.task.dto.SafeTaskPayloadProjector;
import com.agentflow.agent.trace.dto.PublicTaskTraceResponse;
import com.agentflow.agent.trace.dto.TaskTraceView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;

/** Explicit snapshot allowlists and safe projection of the existing internal Trace DTO. */
@Component
public class PublicTaskTraceProjector {
    private final ObjectMapper objectMapper;
    private final SafeTaskPayloadProjector payloadProjector;

    public PublicTaskTraceProjector(ObjectMapper objectMapper, SafeTaskPayloadProjector payloadProjector) {
        this.objectMapper = objectMapper;
        this.payloadProjector = payloadProjector;
    }

    public List<PublicTaskTraceResponse.Step> steps(TaskTraceView trace) {
        JsonNode safe = payloadProjector.project(objectMapper.valueToTree(trace.steps()));
        for (JsonNode step : safe) {
            for (JsonNode call : step.path("llmCalls")) {
                ObjectNode request = pick(call.path("requestSnapshot"), "messages", "modelProvider", "modelName",
                        "provider", "model", "requestedModel", "temperature", "topP", "maxOutputTokens");
                ArrayNode messages = objectMapper.createArrayNode();
                for (JsonNode message : request.path("messages")) {
                    messages.add(pick(message, "role", "content"));
                }
                request.set("messages", messages);
                ((ObjectNode) call).set("requestSnapshot", request);
            }
        }
        return objectMapper.convertValue(safe, new TypeReference<List<PublicTaskTraceResponse.Step>>() { });
    }

    public JsonNode executionSnapshot(String serialized) {
        JsonNode snapshot = payloadProjector.parse(serialized);
        ObjectNode result = pick(snapshot, "snapshotVersion");
        result.set("agent", pick(snapshot.path("agent"), "agentId", "systemPrompt", "status",
                "maxDecisionTurns", "maxToolCalls", "maxTotalTokens", "timeoutSeconds"));
        result.set("runtime", pick(snapshot.path("runtime"), "decisionProtocolVersion",
                "promptRulesVersion", "applicationRevision"));
        result.set("chatModel", pick(snapshot.path("chatModel"), "profileCode", "provider", "model",
                "temperature", "topP", "contextWindow", "supportsUsage"));
        ObjectNode retrieval = pick(snapshot.path("retrieval"), "topK", "similarityThreshold", "useRerank");
        ArrayNode knowledgeBases = objectMapper.createArrayNode();
        for (JsonNode knowledgeBase : snapshot.path("retrieval").path("knowledgeBases")) {
            ObjectNode projected = pick(knowledgeBase, "knowledgeBaseId", "embeddingProfileCode", "chunkStrategyVersion");
            ArrayNode documents = objectMapper.createArrayNode();
            for (JsonNode document : knowledgeBase.path("documents")) {
                documents.add(pick(document, "documentId", "vectorGeneration"));
            }
            projected.set("documents", documents);
            knowledgeBases.add(projected);
        }
        retrieval.set("knowledgeBases", knowledgeBases);
        result.set("retrieval", retrieval);
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode tool : snapshot.path("tools")) {
            tools.add(pick(tool, "toolId", "toolCode", "name", "description", "inputSchema",
                    "inputSchemaHash", "implementationVersion", "timeoutMs"));
        }
        result.set("tools", tools);
        return result;
    }

    private ObjectNode pick(JsonNode source, String... fields) {
        ObjectNode result = objectMapper.createObjectNode();
        for (String field : fields) {
            if (source.has(field)) {
                result.set(field, source.get(field).deepCopy());
            }
        }
        return result;
    }
}
