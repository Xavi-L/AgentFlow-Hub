package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeRetrievalResponse;
import com.agentflow.knowledge.dto.RetrieveTestRequest;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.agentflow.knowledge.model.KnowledgeChunk;
import com.agentflow.knowledge.repository.KnowledgeBaseMapper;
import com.agentflow.knowledge.repository.KnowledgeChunkMapper;
import com.agentflow.knowledge.vector.EmbeddingGateway;
import com.agentflow.knowledge.vector.EmbeddingRequest;
import com.agentflow.knowledge.vector.EmbeddingVector;
import com.agentflow.knowledge.vector.VectorSearchHit;
import com.agentflow.knowledge.vector.VectorSearchRequest;
import com.agentflow.knowledge.vector.VectorStoreGateway;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {
    private static final String FIRST_VECTOR_ID = "6f221541-64ae-8c32-9f22-c44f515cd6a0";
    private static final String SECOND_VECTOR_ID = "f7dcc320-8d47-8d9a-8407-a58b99f9812b";

    @BeforeAll
    static void initializeLambdaCaches() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(),
                "KnowledgeRetrievalServiceTest"
        );
        assistant.setCurrentNamespace(KnowledgeBaseMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, KnowledgeBase.class);
    }

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private EmbeddingGateway embeddingGateway;

    @Mock
    private VectorStoreGateway vectorStoreGateway;

    @InjectMocks
    private KnowledgeRetrievalService knowledgeRetrievalService;

    @Captor
    private ArgumentCaptor<EmbeddingRequest> embeddingRequestCaptor;

    @Captor
    private ArgumentCaptor<VectorSearchRequest> vectorSearchRequestCaptor;

    @Test
    void shouldSearchWithinFixedScopeThenReturnCanonicalChunksInQdrantOrder() {
        KnowledgeChunk firstChunk = completedChunk(401L, 301L, 0, FIRST_VECTOR_ID, "退款错误码说明");
        KnowledgeChunk secondChunk = completedChunk(402L, 302L, 1, SECOND_VECTOR_ID, "支付渠道退款失败排查");
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(activeKnowledgeBase());
        when(embeddingGateway.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f, 0.2f, 0.3f)));
        when(vectorStoreGateway.search(any())).thenReturn(List.of(
                new VectorSearchHit(SECOND_VECTOR_ID, 402L, 0.97),
                new VectorSearchHit(FIRST_VECTOR_ID, 401L, 0.88)
        ));
        when(knowledgeChunkMapper.selectRetrievableChunks(201L, 101L, List.of(402L, 401L)))
                .thenReturn(List.of(firstChunk, secondChunk));

        KnowledgeRetrievalResponse response = knowledgeRetrievalService.retrieveTest(
                currentUser(), 201L, new RetrieveTestRequest("  退款失败如何排查？  ", null)
        );

        verify(embeddingGateway).embed(embeddingRequestCaptor.capture());
        assertThat(embeddingRequestCaptor.getValue()).isEqualTo(
                new EmbeddingRequest("退款失败如何排查？", "dashscope", "text-embedding-v4")
        );
        verify(vectorStoreGateway).search(vectorSearchRequestCaptor.capture());
        assertThat(vectorSearchRequestCaptor.getValue()).isEqualTo(new VectorSearchRequest(
                new EmbeddingVector(List.of(0.1f, 0.2f, 0.3f)), 101L, 201L, 5
        ));
        assertThat(response.query()).isEqualTo("退款失败如何排查？");
        assertThat(response.topK()).isEqualTo(5);
        assertThat(response.items()).extracting(item -> item.chunkId()).containsExactly("402", "401");
        assertThat(response.items()).extracting(item -> item.rank()).containsExactly(1, 2);
        assertThat(response.items()).extracting(item -> item.score()).containsExactly(0.97, 0.88);
        assertThat(response.items()).extracting(item -> item.fileName())
                .containsExactly("refund-guide.md", "refund-guide.md");
        assertThat(response.items()).extracting(item -> item.tokenCount()).containsExactly(8, 8);
    }

    @Test
    void shouldNotReturnAHitWhoseCurrentPostgresVectorIdentityNoLongerMatches() {
        KnowledgeChunk staleChunk = completedChunk(
                401L, 301L, 0, SECOND_VECTOR_ID, "已被重新处理的退款说明"
        );
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(activeKnowledgeBase());
        when(embeddingGateway.embed(any())).thenReturn(new EmbeddingVector(List.of(0.1f, 0.2f, 0.3f)));
        when(vectorStoreGateway.search(any())).thenReturn(List.of(
                new VectorSearchHit(FIRST_VECTOR_ID, 401L, 0.97)
        ));
        when(knowledgeChunkMapper.selectRetrievableChunks(201L, 101L, List.of(401L)))
                .thenReturn(List.of(staleChunk));

        KnowledgeRetrievalResponse response = knowledgeRetrievalService.retrieveTest(
                currentUser(), 201L, new RetrieveTestRequest("退款规则", 3)
        );

        assertThat(response.items()).isEmpty();
    }

    @Test
    void shouldHideMissingOrUnownedKnowledgeBasesBeforeAnyExternalCall() {
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> knowledgeRetrievalService.retrieveTest(
                currentUser(), 201L, new RetrieveTestRequest("退款规则", 3)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMON_NOT_FOUND);

        verify(embeddingGateway, never()).embed(any());
        verify(vectorStoreGateway, never()).search(any());
        verify(knowledgeChunkMapper, never()).selectRetrievableChunks(any(), any(), any());
    }

    @Test
    void shouldRejectDisabledKnowledgeBasesBeforeEmbeddingTheQuery() {
        KnowledgeBase disabledKnowledgeBase = activeKnowledgeBase();
        disabledKnowledgeBase.setStatus("DISABLED");
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(disabledKnowledgeBase);

        assertThatThrownBy(() -> knowledgeRetrievalService.retrieveTest(
                currentUser(), 201L, new RetrieveTestRequest("退款规则", 3)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.KNOWLEDGE_BASE_NOT_ACTIVE);

        verify(embeddingGateway, never()).embed(any());
        verify(vectorStoreGateway, never()).search(any());
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeBase activeKnowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(201L);
        knowledgeBase.setUserId(101L);
        knowledgeBase.setStatus("ACTIVE");
        knowledgeBase.setEmbeddingProvider("dashscope");
        knowledgeBase.setEmbeddingModel("text-embedding-v4");
        return knowledgeBase;
    }

    private static KnowledgeChunk completedChunk(
            Long id,
            Long documentId,
            int chunkIndex,
            String vectorId,
            String content
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setUserId(101L);
        chunk.setKnowledgeBaseId(201L);
        chunk.setDocumentId(documentId);
        chunk.setDocumentFileName("refund-guide.md");
        chunk.setChunkIndex(chunkIndex);
        chunk.setTitlePath("支付 / 退款");
        chunk.setContent(content);
        chunk.setTokenCount(8);
        chunk.setVectorizationStatus("COMPLETED");
        chunk.setVectorId(vectorId);
        return chunk;
    }
}
