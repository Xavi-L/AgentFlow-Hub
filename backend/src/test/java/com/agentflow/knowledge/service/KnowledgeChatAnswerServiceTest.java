package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerResponse;
import com.agentflow.knowledge.dto.KnowledgeChatResponse;
import com.agentflow.knowledge.dto.KnowledgeContextSourceResponse;
import com.agentflow.knowledge.model.KnowledgeChatAnswer;
import com.agentflow.knowledge.repository.KnowledgeChatAnswerMapper;
import com.agentflow.user.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeChatAnswerServiceTest {

    @Mock
    private KnowledgeChatService knowledgeChatService;

    @Mock
    private KnowledgeChatAnswerMapper knowledgeChatAnswerMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private KnowledgeChatAnswerService knowledgeChatAnswerService;

    @BeforeEach
    void setUp() {
        knowledgeChatAnswerService = new KnowledgeChatAnswerService(
                knowledgeChatService,
                knowledgeChatAnswerMapper,
                objectMapper
        );
    }

    @Test
    void shouldPersistOnlyTheValidatedV9ResultAsAnAnswerAuditSnapshot() throws Exception {
        ChatTestRequest request = request();
        KnowledgeChatResponse generated = generatedResponse();
        when(knowledgeChatService.chatTest(currentUser(), 201L, request)).thenReturn(generated);
        when(knowledgeChatAnswerMapper.insert(any(KnowledgeChatAnswer.class))).thenAnswer(invocation -> {
            KnowledgeChatAnswer inserted = invocation.getArgument(0);
            inserted.setId(501L);
            return 1;
        });

        KnowledgeChatAnswerResponse response = knowledgeChatAnswerService.chat(
                currentUser(),
                201L,
                request
        );

        ArgumentCaptor<KnowledgeChatAnswer> recordCaptor = ArgumentCaptor.forClass(KnowledgeChatAnswer.class);
        verify(knowledgeChatAnswerMapper).insert(recordCaptor.capture());
        KnowledgeChatAnswer persisted = recordCaptor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(101L);
        assertThat(persisted.getKnowledgeBaseId()).isEqualTo(201L);
        assertThat(persisted.getQuery()).isEqualTo(generated.query());
        assertThat(persisted.getAnswer()).isEqualTo(generated.answer());
        assertThat(persisted.getTopK()).isEqualTo(generated.topK());
        assertThat(persisted.getMaxContextTokens()).isEqualTo(generated.maxContextTokens());
        assertThat(persisted.getUsedContextTokens()).isEqualTo(generated.usedContextTokens());
        assertThat(persisted.getSkippedChunkCount()).isEqualTo(generated.skippedChunkCount());
        assertThat(persisted.getMaxAnswerTokens()).isEqualTo(generated.maxAnswerTokens());
        assertThat(objectMapper.readTree(persisted.getSourcesSnapshotJson()).get(0).get("fileName").asText())
                .isEqualTo("refund-rules.md");
        assertThat(objectMapper.readTree(persisted.getCitationIdsJson()))
                .extracting(node -> node.asText())
                .containsExactly("S2", "S1");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(response.answerId()).isEqualTo("501");
        assertThat(response.answer()).isEqualTo(generated.answer());
        assertThat(response.sources()).containsExactlyElementsOf(generated.sources());
        assertThat(response.citationIds()).containsExactly("S2", "S1");
        assertThat(response.createdAt()).isEqualTo(persisted.getCreatedAt());
        verify(knowledgeChatService).chatTest(currentUser(), 201L, request);
    }

    @Test
    void shouldNotPersistWhenV9RejectsTheGeneratedAnswer() {
        when(knowledgeChatService.chatTest(currentUser(), 201L, request()))
                .thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID));

        assertBusinessCode(
                () -> knowledgeChatAnswerService.chat(currentUser(), 201L, request()),
                ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID
        );

        verifyNoInteractions(knowledgeChatAnswerMapper);
    }

    @Test
    void shouldReadTheFrozenSourceSnapshotWithoutCallingV9Again() {
        KnowledgeChatAnswer stored = storedAnswer();
        when(knowledgeChatAnswerMapper.selectOwnedById(501L, 201L, 101L)).thenReturn(stored);

        KnowledgeChatAnswerResponse response = knowledgeChatAnswerService.getById(
                currentUser(),
                201L,
                501L
        );

        assertThat(response.answerId()).isEqualTo("501");
        assertThat(response.answer()).isEqualTo("历史规则要求先核对错误码。[S1]");
        assertThat(response.maxContextTokens()).isEqualTo(800);
        assertThat(response.usedContextTokens()).isEqualTo(63);
        assertThat(response.sources()).singleElement().satisfies(source -> {
            assertThat(source.citationId()).isEqualTo("S1");
            assertThat(source.fileName()).isEqualTo("historical-refund-rules.md");
            assertThat(source.titlePath()).isEqualTo("历史 / 退款");
        });
        assertThat(response.citationIds()).containsExactly("S1");
        assertThat(response.createdAt()).isEqualTo(stored.getCreatedAt());
        verify(knowledgeChatAnswerMapper).selectOwnedById(501L, 201L, 101L);
        verifyNoInteractions(knowledgeChatService);
    }

    @Test
    void shouldHideAnAbsentOrOtherOwnersAnswerBehindTheSameNotFoundContract() {
        when(knowledgeChatAnswerMapper.selectOwnedById(501L, 201L, 101L)).thenReturn(null);

        assertBusinessCode(
                () -> knowledgeChatAnswerService.getById(currentUser(), 201L, 501L),
                ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND
        );

        verify(knowledgeChatAnswerMapper).selectOwnedById(501L, 201L, 101L);
        verifyNoInteractions(knowledgeChatService);
    }

    @Test
    void shouldFailFastWhenTheAuditInsertDoesNotCreateExactlyOneRow() {
        when(knowledgeChatService.chatTest(currentUser(), 201L, request())).thenReturn(generatedResponse());
        when(knowledgeChatAnswerMapper.insert(any(KnowledgeChatAnswer.class))).thenReturn(0);

        assertThatThrownBy(() -> knowledgeChatAnswerService.chat(currentUser(), 201L, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one inserted knowledge_chat_answer row");
    }

    private static ChatTestRequest request() {
        return new ChatTestRequest("退款失败如何排查？", 3, 800, 256);
    }

    private static KnowledgeChatResponse generatedResponse() {
        return new KnowledgeChatResponse(
                "先检查支付渠道错误码。[S2] 再核对退款规则。[S1]",
                "退款失败如何排查？",
                3,
                800,
                63,
                1,
                256,
                List.of(
                        new KnowledgeContextSourceResponse(
                                "S1",
                                "401",
                                "301",
                                "refund-rules.md",
                                "支付 / 退款",
                                0.93
                        ),
                        new KnowledgeContextSourceResponse(
                                "S2",
                                "402",
                                "302",
                                "payment-status.txt",
                                "",
                                0.88
                        )
                ),
                List.of("S2", "S1")
        );
    }

    private static KnowledgeChatAnswer storedAnswer() {
        KnowledgeChatAnswer answer = new KnowledgeChatAnswer();
        answer.setId(501L);
        answer.setUserId(101L);
        answer.setKnowledgeBaseId(201L);
        answer.setQuery("退款失败如何排查？");
        answer.setAnswer("历史规则要求先核对错误码。[S1]");
        answer.setTopK(3);
        answer.setMaxContextTokens(800);
        answer.setUsedContextTokens(63);
        answer.setSkippedChunkCount(1);
        answer.setMaxAnswerTokens(256);
        answer.setSourcesSnapshotJson("""
                [{"citationId":"S1","chunkId":"401","documentId":"301","fileName":"historical-refund-rules.md","titlePath":"历史 / 退款","score":0.93}]
                """);
        answer.setCitationIdsJson("[\"S1\"]");
        answer.setCreatedAt(OffsetDateTime.parse("2026-08-25T10:30:00+08:00"));
        return answer;
    }

    private static void assertBusinessCode(Runnable invocation, ErrorCode expectedCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(expectedCode);
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
