package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.chunk.LightweightTokenEstimator;
import com.agentflow.knowledge.dto.KnowledgeContextResponse;
import com.agentflow.knowledge.dto.KnowledgeRetrievalResponse;
import com.agentflow.knowledge.dto.RetrieveContextTestRequest;
import com.agentflow.knowledge.dto.RetrieveTestRequest;
import com.agentflow.knowledge.dto.RetrievedChunkResponse;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeContextServiceTest {

    @Mock
    private KnowledgeRetrievalService knowledgeRetrievalService;

    private KnowledgeContextService knowledgeContextService;

    @BeforeEach
    void setUp() {
        knowledgeContextService = new KnowledgeContextService(
                knowledgeRetrievalService,
                new LightweightTokenEstimator()
        );
    }

    @Test
    void shouldReuseV7OrderAssignStableCitationsAndSkipOnlyOversizedChunks() {
        RetrieveContextTestRequest request = new RetrieveContextTestRequest(
                "  退款失败如何排查？  ",
                3,
                150
        );
        when(knowledgeRetrievalService.retrieveTest(
                currentUser(),
                201L,
                new RetrieveTestRequest("  退款失败如何排查？  ", 3)
        )).thenReturn(new KnowledgeRetrievalResponse(
                "退款失败如何排查？",
                3,
                List.of(
                        retrievedChunk(
                                1,
                                0.97,
                                "401",
                                "301",
                                "refund-rules.md",
                                "支付 / 退款",
                                7,
                                "第一条可用内容"
                        ),
                        retrievedChunk(
                                2,
                                0.93,
                                "402",
                                "302",
                                "large-refund-guide.md",
                                "支付 / 大段说明",
                                1_000,
                                "不应混入上下文".repeat(100)
                        ),
                        retrievedChunk(
                                3,
                                0.88,
                                "403",
                                "303",
                                "payment-status.txt",
                                null,
                                6,
                                "第三条短内容"
                        )
                )
        ));

        KnowledgeContextResponse response = knowledgeContextService.retrieveContextTest(
                currentUser(),
                201L,
                request
        );

        assertThat(response.query()).isEqualTo("退款失败如何排查？");
        assertThat(response.topK()).isEqualTo(3);
        assertThat(response.maxContextTokens()).isEqualTo(150);
        assertThat(response.usedContextTokens()).isPositive().isLessThanOrEqualTo(150);
        assertThat(response.skippedChunkCount()).isEqualTo(1);
        assertThat(response.sources()).extracting(source -> source.citationId()).containsExactly("S1", "S2");
        assertThat(response.sources()).extracting(source -> source.chunkId()).containsExactly("401", "403");
        assertThat(response.sources()).extracting(source -> source.documentId()).containsExactly("301", "303");
        assertThat(response.sources()).extracting(source -> source.fileName())
                .containsExactly("refund-rules.md", "payment-status.txt");
        assertThat(response.sources()).extracting(source -> source.titlePath()).containsExactly("支付 / 退款", "");
        assertThat(response.sources()).extracting(source -> source.score()).containsExactly(0.97, 0.88);
        assertThat(response.context()).isEqualTo("""
                [S1]
                Source: refund-rules.md
                Title: 支付 / 退款
                DocumentId: 301
                ChunkId: 401
                Content:
                第一条可用内容

                [S2]
                Source: payment-status.txt
                Title:
                DocumentId: 303
                ChunkId: 403
                Content:
                第三条短内容""");
        assertThat(response.context()).doesNotContain("不应混入上下文");
        verify(knowledgeRetrievalService).retrieveTest(
                currentUser(),
                201L,
                new RetrieveTestRequest("  退款失败如何排查？  ", 3)
        );
    }

    @Test
    void shouldReturnEmptyContextWhenNoWholeChunkFitsInsteadOfTruncatingIt() {
        RetrieveContextTestRequest request = new RetrieveContextTestRequest("退款规则", 1, 1);
        when(knowledgeRetrievalService.retrieveTest(
                currentUser(),
                201L,
                new RetrieveTestRequest("退款规则", 1)
        )).thenReturn(new KnowledgeRetrievalResponse(
                "退款规则",
                1,
                List.of(retrievedChunk(
                        1,
                        0.97,
                        "401",
                        "301",
                        "refund-rules.md",
                        "支付 / 退款",
                        7,
                        "完整正文绝不能被截断"
                ))
        ));

        KnowledgeContextResponse response = knowledgeContextService.retrieveContextTest(
                currentUser(),
                201L,
                request
        );

        assertThat(response.context()).isEmpty();
        assertThat(response.sources()).isEmpty();
        assertThat(response.usedContextTokens()).isZero();
        assertThat(response.skippedChunkCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectAnInvalidBudgetBeforeCallingV7() {
        assertThatThrownBy(() -> knowledgeContextService.retrieveContextTest(
                currentUser(),
                201L,
                new RetrieveContextTestRequest("退款规则", 3, 0)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMON_PARAM_INVALID);

        verifyNoInteractions(knowledgeRetrievalService);
    }

    private static RetrievedChunkResponse retrievedChunk(
            int rank,
            double score,
            String chunkId,
            String documentId,
            String fileName,
            String titlePath,
            int tokenCount,
            String content
    ) {
        return new RetrievedChunkResponse(
                rank,
                score,
                chunkId,
                documentId,
                fileName,
                rank - 1,
                titlePath,
                tokenCount,
                content
        );
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
