package com.agentflow.agent.snapshot;

import com.agentflow.agent.binding.model.BoundKnowledgeBaseRow;
import com.agentflow.agent.binding.model.BoundToolDefinitionRow;
import com.agentflow.agent.binding.model.ReadyDocumentGenerationRow;
import com.agentflow.agent.binding.repository.AgentKnowledgeBindingMapper;
import com.agentflow.agent.binding.repository.AgentToolBindingMapper;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.AgentSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ChatModelSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.DocumentGenerationSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.KnowledgeBaseSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.RetrievalSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.RuntimeSnapshot;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot.ToolSnapshot;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the M4B Agent, model, retrieval corpus, and tool dependencies under one short,
 * repeatable-read database transaction. No LLM, Qdrant, or tool I/O occurs here.
 */
@Service
public class AgentTaskSnapshotResolver {
    public static final String SNAPSHOT_VERSION = "agent-task-snapshot-v1";
    public static final String CHUNK_STRATEGY_VERSION = "structured-token-v1";
    public static final String EMBEDDING_PROFILE_CODE = "dashscope-te-v4-1024-cosine";
    public static final String CHAT_PROFILE_CODE = "openai-compatible-default";
    private static final String IMPLEMENTATION_VERSION = "builtin-v1";
    private static final Map<String, String> ALLOWED_TOOL_HANDLERS = Map.of(
            "order_query", "orderQueryTool",
            "payment_log_query", "paymentLogQueryTool"
    );

    private final AgentAppMapper agentAppMapper;
    private final AgentKnowledgeBindingMapper knowledgeBindingMapper;
    private final AgentToolBindingMapper toolBindingMapper;
    private final ObjectMapper objectMapper;
    private final String applicationRevision;

    public AgentTaskSnapshotResolver(
            AgentAppMapper agentAppMapper,
            AgentKnowledgeBindingMapper knowledgeBindingMapper,
            AgentToolBindingMapper toolBindingMapper,
            ObjectMapper objectMapper,
            @Value("${agentflow.runtime.application-revision:development}") String applicationRevision
    ) {
        this.agentAppMapper = Objects.requireNonNull(agentAppMapper, "agentAppMapper must not be null");
        this.knowledgeBindingMapper = Objects.requireNonNull(
                knowledgeBindingMapper,
                "knowledgeBindingMapper must not be null"
        );
        this.toolBindingMapper = Objects.requireNonNull(toolBindingMapper, "toolBindingMapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.applicationRevision = applicationRevision == null || applicationRevision.isBlank()
                ? "development"
                : applicationRevision.trim();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AgentTaskExecutionSnapshot resolve(Long userId, Long agentId) {
        requirePositive(userId, "userId");
        requirePositive(agentId, "agentId");
        AgentApp agent = agentAppMapper.selectVisibleOwnedByIdForUpdate(agentId, userId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found");
        }
        if (!"ACTIVE".equals(agent.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_DISABLED, "Agent is disabled");
        }

        AgentSnapshot agentSnapshot = resolveAgent(agentId, agent);
        ChatModelSnapshot chatModel = resolveChatModel(agent);
        RetrievalSnapshot retrieval = resolveRetrieval(userId, agentId);
        List<ToolSnapshot> tools = resolveTools(userId, agentId);
        return new AgentTaskExecutionSnapshot(
                SNAPSHOT_VERSION,
                agentSnapshot,
                new RuntimeSnapshot(
                        "agent-decision-json-v1",
                        "agent-runtime-rules-v1",
                        applicationRevision
                ),
                chatModel,
                retrieval,
                tools
        );
    }

    private AgentSnapshot resolveAgent(Long agentId, AgentApp agent) {
        if (agent.getSystemPrompt() == null || agent.getSystemPrompt().isBlank()
                || agent.getMaxSteps() == null || agent.getMaxSteps() < 1
                || agent.getMaxToolCalls() == null || agent.getMaxToolCalls() < 0
                || agent.getMaxToolCalls() >= agent.getMaxSteps()
                || agent.getMaxTokens() == null || agent.getMaxTokens() < 256
                || agent.getTimeoutSeconds() == null || agent.getTimeoutSeconds() < 1) {
            throw invalidBinding("Agent execution configuration is invalid");
        }
        return new AgentSnapshot(
                String.valueOf(agentId),
                agent.getSystemPrompt(),
                agent.getStatus(),
                agent.getMaxSteps(),
                agent.getMaxToolCalls(),
                agent.getMaxTokens(),
                agent.getTimeoutSeconds()
        );
    }

    private ChatModelSnapshot resolveChatModel(AgentApp agent) {
        if (!"openai-compatible".equals(agent.getModelProvider())
                || agent.getModelName() == null || agent.getModelName().isBlank()
                || agent.getTemperature() == null || agent.getTopP() == null) {
            throw invalidBinding("Chat model profile is invalid");
        }
        return new ChatModelSnapshot(
                CHAT_PROFILE_CODE,
                agent.getModelProvider(),
                agent.getModelName(),
                agent.getTemperature(),
                agent.getTopP(),
                32_768,
                true
        );
    }

    private RetrievalSnapshot resolveRetrieval(Long userId, Long agentId) {
        List<BoundKnowledgeBaseRow> knowledgeBases =
                knowledgeBindingMapper.selectActiveBoundKnowledgeBases(agentId, userId);
        List<ReadyDocumentGenerationRow> readyDocuments =
                knowledgeBindingMapper.selectReadyDocumentGenerations(agentId, userId);
        Map<Long, List<ReadyDocumentGenerationRow>> documentsByKnowledgeBase = new HashMap<>();
        for (ReadyDocumentGenerationRow document : readyDocuments) {
            if (!CHUNK_STRATEGY_VERSION.equals(document.getChunkStrategyVersion())) {
                throw invalidBinding("Knowledge chunk strategy is unsupported");
            }
            documentsByKnowledgeBase.computeIfAbsent(
                    document.getKnowledgeBaseId(),
                    ignored -> new ArrayList<>()
            ).add(document);
        }

        List<KnowledgeBaseSnapshot> snapshots = new ArrayList<>();
        int readyDocumentCount = 0;
        for (BoundKnowledgeBaseRow knowledgeBase : knowledgeBases) {
            requireFixedEmbeddingProfile(knowledgeBase);
            List<DocumentGenerationSnapshot> documents = documentsByKnowledgeBase
                    .getOrDefault(knowledgeBase.getKnowledgeBaseId(), List.of())
                    .stream()
                    .sorted(Comparator.comparing(ReadyDocumentGenerationRow::getDocumentId))
                    .map(document -> new DocumentGenerationSnapshot(
                            String.valueOf(document.getDocumentId()),
                            document.getVectorGeneration()
                    ))
                    .toList();
            readyDocumentCount += documents.size();
            snapshots.add(new KnowledgeBaseSnapshot(
                    String.valueOf(knowledgeBase.getKnowledgeBaseId()),
                    EMBEDDING_PROFILE_CODE,
                    CHUNK_STRATEGY_VERSION,
                    documents
            ));
        }
        if (readyDocumentCount == 0) {
            throw new BusinessException(
                    ErrorCode.RAG_KNOWLEDGE_NOT_READY,
                    "No bound knowledge document is ready for retrieval"
            );
        }
        return new RetrievalSnapshot(snapshots, 5, new BigDecimal("0.2"), false);
    }

    private List<ToolSnapshot> resolveTools(Long userId, Long agentId) {
        return toolBindingMapper.selectSnapshotTools(agentId, userId).stream()
                .map(this::resolveTool)
                .toList();
    }

    private ToolSnapshot resolveTool(BoundToolDefinitionRow row) {
        try {
            JsonNode inputSchema = objectMapper.readTree(row.getInputSchemaJson());
            JsonNode config = objectMapper.readTree(row.getConfigJson());
            String expectedHandler = ALLOWED_TOOL_HANDLERS.get(row.getToolCode());
            if (expectedHandler == null
                    || config == null || !config.isObject()
                    || !expectedHandler.equals(config.path("handler").asText())
                    || !config.path("readonly").asBoolean(false)
                    || inputSchema == null || !inputSchema.isObject()
                    || row.getTimeoutMs() == null || row.getTimeoutMs() <= 0) {
                throw invalidBinding("Tool definition is invalid");
            }
            JsonNode configuredVersion = config.get("implementationVersion");
            if (configuredVersion != null
                    && (!configuredVersion.isTextual()
                    || !IMPLEMENTATION_VERSION.equals(configuredVersion.textValue()))) {
                throw invalidBinding("Tool implementation version is unsupported");
            }
            return new ToolSnapshot(
                    String.valueOf(row.getToolId()),
                    row.getToolCode(),
                    row.getName(),
                    row.getDescription(),
                    inputSchema,
                    sha256CanonicalJson(inputSchema),
                    IMPLEMENTATION_VERSION,
                    row.getTimeoutMs()
            );
        } catch (JsonProcessingException ex) {
            throw invalidBinding("Tool definition JSON is invalid");
        }
    }

    private static void requireFixedEmbeddingProfile(BoundKnowledgeBaseRow knowledgeBase) {
        if (!"dashscope".equals(knowledgeBase.getEmbeddingProvider())
                || !"text-embedding-v4".equals(knowledgeBase.getEmbeddingModel())
                || !Integer.valueOf(800).equals(knowledgeBase.getChunkSize())
                || !Integer.valueOf(120).equals(knowledgeBase.getChunkOverlap())) {
            throw invalidBinding("Knowledge base embedding profile is unsupported");
        }
    }

    private String sha256CanonicalJson(JsonNode value) {
        try {
            String canonicalJson = objectMapper.writeValueAsString(canonicalize(value));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to hash tool schema", ex);
        }
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = JsonNodeFactory.instance.objectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> canonical.set(field, canonicalize(value.get(field))));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        return value.deepCopy();
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, field + " must be positive");
        }
    }

    private static BusinessException invalidBinding(String message) {
        return new BusinessException(ErrorCode.AGENT_BINDING_INVALID, message);
    }
}
