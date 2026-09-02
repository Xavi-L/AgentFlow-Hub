package com.agentflow.agent.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.agentflow.agent.binding.model.BoundKnowledgeBaseRow;
import com.agentflow.agent.binding.model.BoundToolDefinitionRow;
import com.agentflow.agent.binding.model.ReadyDocumentGenerationRow;
import com.agentflow.agent.binding.repository.AgentKnowledgeBindingMapper;
import com.agentflow.agent.binding.repository.AgentToolBindingMapper;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class AgentTaskSnapshotResolverTest {
    @Mock
    private AgentAppMapper agentAppMapper;
    @Mock
    private AgentKnowledgeBindingMapper knowledgeBindingMapper;
    @Mock
    private AgentToolBindingMapper toolBindingMapper;

    private AgentTaskSnapshotResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentTaskSnapshotResolver(
                agentAppMapper,
                knowledgeBindingMapper,
                toolBindingMapper,
                new ObjectMapper(),
                "de90d98"
        );
    }

    @Test
    void shouldResolveOneImmutableCanonicalExecutionSnapshot() {
        AgentApp agent = activeAgent();
        BoundKnowledgeBaseRow knowledgeBase = knowledgeBase(201L);
        ReadyDocumentGenerationRow document = readyDocument(201L, 501L, 3L);
        BoundToolDefinitionRow tool = tool(
                270000000000000001L,
                "order_query",
                "{\"b\":1,\"a\":2}",
                "{\"handler\":\"orderQueryTool\",\"readonly\":true}"
        );
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(agent);
        when(knowledgeBindingMapper.selectActiveBoundKnowledgeBases(301L, 101L))
                .thenReturn(List.of(knowledgeBase));
        when(knowledgeBindingMapper.selectReadyDocumentGenerations(301L, 101L))
                .thenReturn(List.of(document));
        when(toolBindingMapper.selectSnapshotTools(301L, 101L)).thenReturn(List.of(tool));

        AgentTaskExecutionSnapshot snapshot = resolver.resolve(101L, 301L);

        assertThat(snapshot.snapshotVersion()).isEqualTo("agent-task-snapshot-v1");
        assertThat(snapshot.agent().agentId()).isEqualTo("301");
        assertThat(snapshot.agent().maxDecisionTurns()).isEqualTo(6);
        assertThat(snapshot.runtime().applicationRevision()).isEqualTo("de90d98");
        assertThat(snapshot.chatModel().profileCode()).isEqualTo("openai-compatible-default");
        assertThat(snapshot.retrieval().knowledgeBases()).singleElement().satisfies(kb -> {
            assertThat(kb.knowledgeBaseId()).isEqualTo("201");
            assertThat(kb.embeddingProfileCode()).isEqualTo("dashscope-te-v4-1024-cosine");
            assertThat(kb.chunkStrategyVersion()).isEqualTo("structured-token-v1");
            assertThat(kb.documents()).containsExactly(
                    new AgentTaskExecutionSnapshot.DocumentGenerationSnapshot("501", 3L)
            );
        });
        assertThat(snapshot.tools()).singleElement().satisfies(resolvedTool -> {
            assertThat(resolvedTool.toolCode()).isEqualTo("order_query");
            assertThat(resolvedTool.implementationVersion()).isEqualTo("builtin-v1");
            assertThat(resolvedTool.inputSchemaHash()).isEqualTo(
                    "d3626ac30a87e6f7a6428233b3c68299976865fa5508e4267c5415c76af7a772"
            );
            ((ObjectNode) resolvedTool.inputSchema()).put("mutated", true);
            assertThat(resolvedTool.inputSchema().has("mutated")).isFalse();
        });

        agent.setSystemPrompt("changed later");
        knowledgeBase.setKnowledgeBaseId(999L);
        tool.setName("changed later");
        assertThat(snapshot.agent().systemPrompt()).isEqualTo("Diagnose payment failures.");
        assertThat(snapshot.retrieval().knowledgeBases().getFirst().knowledgeBaseId()).isEqualTo("201");
        assertThat(snapshot.tools().getFirst().name()).isEqualTo("Order Query");
        assertThatThrownBy(() -> snapshot.tools().add(snapshot.tools().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldFailBeforeTaskCreationWhenNoBoundDocumentIsReady() {
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(activeAgent());
        when(knowledgeBindingMapper.selectActiveBoundKnowledgeBases(301L, 101L))
                .thenReturn(List.of(knowledgeBase(201L)));
        when(knowledgeBindingMapper.selectReadyDocumentGenerations(301L, 101L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(101L, 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RAG_KNOWLEDGE_NOT_READY));
    }

    @Test
    void shouldRejectDisabledAgentBeforeReadingBindings() {
        AgentApp agent = activeAgent();
        agent.setStatus("DISABLED");
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(agent);

        assertThatThrownBy(() -> resolver.resolve(101L, 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_DISABLED));
    }

    @Test
    void shouldRejectAnIncompatibleEmbeddingProfile() {
        BoundKnowledgeBaseRow knowledgeBase = knowledgeBase(201L);
        knowledgeBase.setEmbeddingModel("text-embedding-v3");
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(activeAgent());
        when(knowledgeBindingMapper.selectActiveBoundKnowledgeBases(301L, 101L))
                .thenReturn(List.of(knowledgeBase));
        when(knowledgeBindingMapper.selectReadyDocumentGenerations(301L, 101L))
                .thenReturn(List.of(readyDocument(201L, 501L, 0L)));

        assertThatThrownBy(() -> resolver.resolve(101L, 301L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_BINDING_INVALID));
    }

    @Test
    void shouldUseRepeatableReadForTheDatabaseOnlySnapshotBoundary() throws Exception {
        Transactional transactional = AgentTaskSnapshotResolver.class
                .getMethod("resolve", Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private static AgentApp activeAgent() {
        AgentApp agent = new AgentApp();
        agent.setId(301L);
        agent.setSystemPrompt("Diagnose payment failures.");
        agent.setModelProvider("openai-compatible");
        agent.setModelName("qwen3");
        agent.setTemperature(new BigDecimal("0.2"));
        agent.setTopP(new BigDecimal("0.8"));
        agent.setMaxSteps(6);
        agent.setMaxToolCalls(4);
        agent.setMaxTokens(8000);
        agent.setTimeoutSeconds(120);
        agent.setStatus("ACTIVE");
        return agent;
    }

    private static BoundKnowledgeBaseRow knowledgeBase(long id) {
        BoundKnowledgeBaseRow row = new BoundKnowledgeBaseRow();
        row.setKnowledgeBaseId(id);
        row.setEmbeddingProvider("dashscope");
        row.setEmbeddingModel("text-embedding-v4");
        row.setChunkSize(800);
        row.setChunkOverlap(120);
        return row;
    }

    private static ReadyDocumentGenerationRow readyDocument(long kbId, long documentId, long generation) {
        ReadyDocumentGenerationRow row = new ReadyDocumentGenerationRow();
        row.setKnowledgeBaseId(kbId);
        row.setDocumentId(documentId);
        row.setVectorGeneration(generation);
        row.setChunkStrategyVersion("structured-token-v1");
        return row;
    }

    private static BoundToolDefinitionRow tool(long id, String code, String schema, String config) {
        BoundToolDefinitionRow row = new BoundToolDefinitionRow();
        row.setToolId(id);
        row.setToolCode(code);
        row.setName("Order Query");
        row.setDescription("Read an order");
        row.setInputSchemaJson(schema);
        row.setConfigJson(config);
        row.setTimeoutMs(3000);
        return row;
    }
}
