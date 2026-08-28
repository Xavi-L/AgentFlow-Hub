package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackResponse;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackVerdict;
import com.agentflow.knowledge.repository.KnowledgeChatAnswerFeedbackMapper;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeChatAnswerFeedbackServiceTest {

    @Mock
    private KnowledgeChatAnswerFeedbackMapper knowledgeChatAnswerFeedbackMapper;

    private KnowledgeChatAnswerFeedbackService knowledgeChatAnswerFeedbackService;

    @BeforeEach
    void setUp() {
        knowledgeChatAnswerFeedbackService = new KnowledgeChatAnswerFeedbackService(
                knowledgeChatAnswerFeedbackMapper
        );
    }

    @Test
    void shouldCreateOneScopedImmutableFeedbackEventWithoutAnyRetrievalOrModelDependency() {
        KnowledgeChatAnswerFeedbackRequest request = request(KnowledgeChatAnswerFeedbackVerdict.HELPFUL);
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(null);
        when(knowledgeChatAnswerFeedbackMapper.insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        )).thenReturn(1);

        KnowledgeChatAnswerFeedbackResponse response = knowledgeChatAnswerFeedbackService.submitFeedback(
                currentUser(),
                201L,
                501L,
                request
        );

        ArgumentCaptor<KnowledgeChatAnswerFeedback> feedbackCaptor = ArgumentCaptor.forClass(
                KnowledgeChatAnswerFeedback.class
        );
        InOrder inOrder = inOrder(knowledgeChatAnswerFeedbackMapper);
        inOrder.verify(knowledgeChatAnswerFeedbackMapper).selectOwnedByAnswerId(501L, 201L, 101L);
        inOrder.verify(knowledgeChatAnswerFeedbackMapper).insertIfAbsent(
                feedbackCaptor.capture(),
                eq(201L),
                eq(101L)
        );
        KnowledgeChatAnswerFeedback persisted = feedbackCaptor.getValue();
        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getAnswerId()).isEqualTo(501L);
        assertThat(persisted.getVerdict()).isEqualTo("HELPFUL");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(response.feedbackId()).isEqualTo(persisted.getId().toString());
        assertThat(response.answerId()).isEqualTo("501");
        assertThat(response.verdict()).isEqualTo(KnowledgeChatAnswerFeedbackVerdict.HELPFUL);
        assertThat(response.createdAt()).isEqualTo(persisted.getCreatedAt());
    }

    @Test
    void shouldCreateANotHelpfulFeedbackEventAsTheFirstAndOnlySubmission() {
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(null);
        when(knowledgeChatAnswerFeedbackMapper.insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        )).thenReturn(1);

        KnowledgeChatAnswerFeedbackResponse response = knowledgeChatAnswerFeedbackService.submitFeedback(
                currentUser(),
                201L,
                501L,
                request(KnowledgeChatAnswerFeedbackVerdict.NOT_HELPFUL)
        );

        assertThat(response.answerId()).isEqualTo("501");
        assertThat(response.verdict()).isEqualTo(KnowledgeChatAnswerFeedbackVerdict.NOT_HELPFUL);
        verify(knowledgeChatAnswerFeedbackMapper).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        );
    }

    @Test
    void shouldReturnTheExistingEventForASameVerdictRetryWithoutWritingAgain() {
        KnowledgeChatAnswerFeedback stored = storedFeedback(701L, 501L, "HELPFUL");
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(stored);

        KnowledgeChatAnswerFeedbackResponse response = knowledgeChatAnswerFeedbackService.submitFeedback(
                currentUser(),
                201L,
                501L,
                request(KnowledgeChatAnswerFeedbackVerdict.HELPFUL)
        );

        assertThat(response.feedbackId()).isEqualTo("701");
        assertThat(response.answerId()).isEqualTo("501");
        assertThat(response.verdict()).isEqualTo(KnowledgeChatAnswerFeedbackVerdict.HELPFUL);
        assertThat(response.createdAt()).isEqualTo(stored.getCreatedAt());
        verify(knowledgeChatAnswerFeedbackMapper).selectOwnedByAnswerId(501L, 201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldRejectAnOppositeVerdictWithoutMutatingTheExistingEvent() {
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(storedFeedback(701L, 501L, "HELPFUL"));

        assertBusinessCode(
                () -> knowledgeChatAnswerFeedbackService.submitFeedback(
                        currentUser(),
                        201L,
                        501L,
                        request(KnowledgeChatAnswerFeedbackVerdict.NOT_HELPFUL)
                ),
                ErrorCode.KNOWLEDGE_CHAT_ANSWER_FEEDBACK_CONFLICT
        );

        verify(knowledgeChatAnswerFeedbackMapper).selectOwnedByAnswerId(501L, 201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldHideMissingForeignAndWrongKnowledgeBaseAnswersBehindTheSameNotFoundContract() {
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(null);
        when(knowledgeChatAnswerFeedbackMapper.insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        )).thenReturn(0);

        assertBusinessCode(
                () -> knowledgeChatAnswerFeedbackService.submitFeedback(
                        currentUser(),
                        201L,
                        501L,
                        request(KnowledgeChatAnswerFeedbackVerdict.HELPFUL)
                ),
                ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND
        );

        InOrder inOrder = inOrder(knowledgeChatAnswerFeedbackMapper);
        inOrder.verify(knowledgeChatAnswerFeedbackMapper).selectOwnedByAnswerId(501L, 201L, 101L);
        inOrder.verify(knowledgeChatAnswerFeedbackMapper).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        );
        inOrder.verify(knowledgeChatAnswerFeedbackMapper).selectOwnedByAnswerId(501L, 201L, 101L);
    }

    @Test
    void shouldResolveAConcurrentSameVerdictInsertRaceToTheExistingEvent() {
        KnowledgeChatAnswerFeedback stored = storedFeedback(702L, 501L, "NOT_HELPFUL");
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(null, stored);
        when(knowledgeChatAnswerFeedbackMapper.insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        )).thenReturn(0);

        KnowledgeChatAnswerFeedbackResponse response = knowledgeChatAnswerFeedbackService.submitFeedback(
                currentUser(),
                201L,
                501L,
                request(KnowledgeChatAnswerFeedbackVerdict.NOT_HELPFUL)
        );

        assertThat(response.feedbackId()).isEqualTo("702");
        assertThat(response.verdict()).isEqualTo(KnowledgeChatAnswerFeedbackVerdict.NOT_HELPFUL);
        verify(knowledgeChatAnswerFeedbackMapper, times(2)).selectOwnedByAnswerId(501L, 201L, 101L);
    }

    @Test
    void shouldResolveAConcurrentOppositeVerdictInsertRaceToConflict() {
        KnowledgeChatAnswerFeedback stored = storedFeedback(702L, 501L, "HELPFUL");
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(null, stored);
        when(knowledgeChatAnswerFeedbackMapper.insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        )).thenReturn(0);

        assertBusinessCode(
                () -> knowledgeChatAnswerFeedbackService.submitFeedback(
                        currentUser(),
                        201L,
                        501L,
                        request(KnowledgeChatAnswerFeedbackVerdict.NOT_HELPFUL)
                ),
                ErrorCode.KNOWLEDGE_CHAT_ANSWER_FEEDBACK_CONFLICT
        );

        verify(knowledgeChatAnswerFeedbackMapper, times(2)).selectOwnedByAnswerId(501L, 201L, 101L);
    }

    @Test
    void shouldFailFastForAnImpossibleInsertCount() {
        when(knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(null);
        when(knowledgeChatAnswerFeedbackMapper.insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                eq(201L),
                eq(101L)
        )).thenReturn(2);

        assertThatThrownBy(() -> knowledgeChatAnswerFeedbackService.submitFeedback(
                currentUser(),
                201L,
                501L,
                request(KnowledgeChatAnswerFeedbackVerdict.HELPFUL)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected zero or one inserted knowledge_chat_answer_feedback row");
    }

    private static KnowledgeChatAnswerFeedbackRequest request(KnowledgeChatAnswerFeedbackVerdict verdict) {
        return new KnowledgeChatAnswerFeedbackRequest(verdict);
    }

    private static KnowledgeChatAnswerFeedback storedFeedback(Long id, Long answerId, String verdict) {
        KnowledgeChatAnswerFeedback feedback = new KnowledgeChatAnswerFeedback();
        feedback.setId(id);
        feedback.setAnswerId(answerId);
        feedback.setVerdict(verdict);
        feedback.setCreatedAt(OffsetDateTime.parse("2026-08-27T10:30:00+08:00"));
        return feedback;
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static void assertBusinessCode(Runnable action, ErrorCode expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getErrorCode())
                        .isEqualTo(expectedCode));
    }
}
