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

import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackCoverageResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackSummaryResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackStatusResponse;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackCoverage;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackSummary;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackStatus;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackVerdict;
import com.agentflow.knowledge.repository.KnowledgeChatAnswerFeedbackMapper;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeChatAnswerFeedbackServiceTest {

    @Mock
    private KnowledgeChatAnswerFeedbackMapper knowledgeChatAnswerFeedbackMapper;

    @Captor
    private ArgumentCaptor<Page<KnowledgeChatAnswerFeedback>> feedbackPageCaptor;

    private KnowledgeChatAnswerFeedbackService knowledgeChatAnswerFeedbackService;

    @BeforeEach
    void setUp() {
        knowledgeChatAnswerFeedbackService = new KnowledgeChatAnswerFeedbackService(
                knowledgeChatAnswerFeedbackMapper
        );
    }

    @Test
    void shouldReadV16CoverageAcrossTheRequiredImmutableAnswerLifecycleWithoutWriting() {
        when(knowledgeChatAnswerFeedbackMapper.selectCoverageOwnedByKnowledgeBase(201L, 101L))
                .thenReturn(
                        coverage(0L, 0L, 0L),
                        coverage(1L, 0L, 1L),
                        coverage(1L, 1L, 0L),
                        coverage(2L, 1L, 1L),
                        coverage(2L, 2L, 0L)
                );

        KnowledgeChatAnswerFeedbackCoverageResponse empty = knowledgeChatAnswerFeedbackService
                .getCoverageOwnedByKnowledgeBase(currentUser(), 201L);
        KnowledgeChatAnswerFeedbackCoverageResponse afterFirstV10 = knowledgeChatAnswerFeedbackService
                .getCoverageOwnedByKnowledgeBase(currentUser(), 201L);
        KnowledgeChatAnswerFeedbackCoverageResponse afterFirstHelpful = knowledgeChatAnswerFeedbackService
                .getCoverageOwnedByKnowledgeBase(currentUser(), 201L);
        KnowledgeChatAnswerFeedbackCoverageResponse afterSecondV10 = knowledgeChatAnswerFeedbackService
                .getCoverageOwnedByKnowledgeBase(currentUser(), 201L);
        KnowledgeChatAnswerFeedbackCoverageResponse afterSecondNotHelpful =
                knowledgeChatAnswerFeedbackService.getCoverageOwnedByKnowledgeBase(
                        currentUser(),
                        201L
                );

        assertCoverage(empty, 0L, 0L, 0L);
        assertCoverage(afterFirstV10, 1L, 0L, 1L);
        assertCoverage(afterFirstHelpful, 1L, 1L, 0L);
        assertCoverage(afterSecondV10, 2L, 1L, 1L);
        assertCoverage(afterSecondNotHelpful, 2L, 2L, 0L);
        verify(knowledgeChatAnswerFeedbackMapper, times(5))
                .selectCoverageOwnedByKnowledgeBase(201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectSummaryOwnedByKnowledgeBase(
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectStatusOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReturnTheSameZeroV16CoverageForEmptyForeignAndWrongKnowledgeBaseScopes() {
        when(knowledgeChatAnswerFeedbackMapper.selectCoverageOwnedByKnowledgeBase(
                anyLong(),
                anyLong()
        )).thenReturn(coverage(0L, 0L, 0L));

        KnowledgeChatAnswerFeedbackCoverageResponse empty = knowledgeChatAnswerFeedbackService
                .getCoverageOwnedByKnowledgeBase(currentUser(), 201L);
        KnowledgeChatAnswerFeedbackCoverageResponse wrongKnowledgeBase =
                knowledgeChatAnswerFeedbackService.getCoverageOwnedByKnowledgeBase(
                        currentUser(),
                        202L
                );
        KnowledgeChatAnswerFeedbackCoverageResponse foreignOwner = knowledgeChatAnswerFeedbackService
                .getCoverageOwnedByKnowledgeBase(
                        new AuthenticatedUser(102L, "other_owner", "Other", "USER"),
                        201L
                );

        assertCoverage(empty, 0L, 0L, 0L);
        assertCoverage(wrongKnowledgeBase, 0L, 0L, 0L);
        assertCoverage(foreignOwner, 0L, 0L, 0L);
        verify(knowledgeChatAnswerFeedbackMapper).selectCoverageOwnedByKnowledgeBase(201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper).selectCoverageOwnedByKnowledgeBase(202L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper).selectCoverageOwnedByKnowledgeBase(201L, 102L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectSummaryOwnedByKnowledgeBase(
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectStatusOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReadRawV15CountsForFirstHelpfulThenAnIndependentNotHelpfulEventWithoutWriting() {
        when(knowledgeChatAnswerFeedbackMapper.selectSummaryOwnedByKnowledgeBase(201L, 101L))
                .thenReturn(
                        summary(1L, 1L, 0L),
                        summary(2L, 1L, 1L)
                );

        KnowledgeChatAnswerFeedbackSummaryResponse afterFirstHelpful =
                knowledgeChatAnswerFeedbackService.getSummaryOwnedByKnowledgeBase(
                        currentUser(),
                        201L
                );
        KnowledgeChatAnswerFeedbackSummaryResponse afterIndependentNotHelpful =
                knowledgeChatAnswerFeedbackService.getSummaryOwnedByKnowledgeBase(
                        currentUser(),
                        201L
                );

        assertSummary(afterFirstHelpful, 1L, 1L, 0L);
        assertSummary(afterIndependentNotHelpful, 2L, 1L, 1L);
        verify(knowledgeChatAnswerFeedbackMapper, times(2))
                .selectSummaryOwnedByKnowledgeBase(201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectStatusOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReturnTheSameZeroV15SummaryForEmptyForeignAndWrongKnowledgeBaseScopes() {
        when(knowledgeChatAnswerFeedbackMapper.selectSummaryOwnedByKnowledgeBase(
                anyLong(),
                anyLong()
        )).thenReturn(summary(0L, 0L, 0L));

        KnowledgeChatAnswerFeedbackSummaryResponse empty = knowledgeChatAnswerFeedbackService
                .getSummaryOwnedByKnowledgeBase(currentUser(), 201L);
        KnowledgeChatAnswerFeedbackSummaryResponse wrongKnowledgeBase =
                knowledgeChatAnswerFeedbackService.getSummaryOwnedByKnowledgeBase(
                        currentUser(),
                        202L
                );
        KnowledgeChatAnswerFeedbackSummaryResponse foreignOwner = knowledgeChatAnswerFeedbackService
                .getSummaryOwnedByKnowledgeBase(
                        new AuthenticatedUser(102L, "other_owner", "Other", "USER"),
                        201L
                );

        assertSummary(empty, 0L, 0L, 0L);
        assertSummary(wrongKnowledgeBase, 0L, 0L, 0L);
        assertSummary(foreignOwner, 0L, 0L, 0L);
        verify(knowledgeChatAnswerFeedbackMapper).selectSummaryOwnedByKnowledgeBase(201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper).selectSummaryOwnedByKnowledgeBase(202L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper).selectSummaryOwnedByKnowledgeBase(201L, 102L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectStatusOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReadAnUnsubmittedV13StatusWithoutWritingOrCallingGenerationDependencies() {
        when(knowledgeChatAnswerFeedbackMapper.selectStatusOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(status(501L, null, null));

        KnowledgeChatAnswerFeedbackStatusResponse response = knowledgeChatAnswerFeedbackService
                .getFeedbackStatus(currentUser(), 201L, 501L);

        assertThat(response.submitted()).isFalse();
        assertThat(response.feedback()).isNull();
        verify(knowledgeChatAnswerFeedbackMapper).selectStatusOwnedByAnswerId(501L, 201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReadTheOriginalV12EventThroughASubmittedV13StatusWithoutWriting() {
        when(knowledgeChatAnswerFeedbackMapper.selectStatusOwnedByAnswerId(501L, 201L, 101L))
                .thenReturn(status(501L, 701L, "HELPFUL"));

        KnowledgeChatAnswerFeedbackStatusResponse response = knowledgeChatAnswerFeedbackService
                .getFeedbackStatus(currentUser(), 201L, 501L);

        assertThat(response.submitted()).isTrue();
        assertThat(response.feedback()).isNotNull();
        assertThat(response.feedback().feedbackId()).isEqualTo("701");
        assertThat(response.feedback().answerId()).isEqualTo("501");
        assertThat(response.feedback().verdict()).isEqualTo(KnowledgeChatAnswerFeedbackVerdict.HELPFUL);
        assertThat(response.feedback().createdAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-27T10:30:00+08:00"));
        verify(knowledgeChatAnswerFeedbackMapper).selectStatusOwnedByAnswerId(501L, 201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldHideMissingForeignAndWrongKnowledgeBaseAnswersBehindTheSameV13NotFoundContract() {
        when(knowledgeChatAnswerFeedbackMapper.selectStatusOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(null);

        assertBusinessCode(
                () -> knowledgeChatAnswerFeedbackService.getFeedbackStatus(currentUser(), 201L, 501L),
                ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND
        );
        assertBusinessCode(
                () -> knowledgeChatAnswerFeedbackService.getFeedbackStatus(currentUser(), 202L, 501L),
                ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND
        );
        assertBusinessCode(
                () -> knowledgeChatAnswerFeedbackService.getFeedbackStatus(
                        new AuthenticatedUser(102L, "other_owner", "Other", "USER"),
                        201L,
                        501L
                ),
                ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND
        );

        verify(knowledgeChatAnswerFeedbackMapper).selectStatusOwnedByAnswerId(501L, 201L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper).selectStatusOwnedByAnswerId(501L, 202L, 101L);
        verify(knowledgeChatAnswerFeedbackMapper).selectStatusOwnedByAnswerId(501L, 201L, 102L);
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldListOnlyCurrentOwnersSubmittedV12EventsInStableTimestampAndIdOrder() {
        OffsetDateTime sharedCreatedAt = OffsetDateTime.parse("2026-08-28T10:30:00+08:00");
        when(knowledgeChatAnswerFeedbackMapper.selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                eq(201L),
                eq(101L)
        )).thenAnswer(invocation -> {
            Page<KnowledgeChatAnswerFeedback> page = invocation.getArgument(0);
            // The mapper's f.created_at DESC, f.id DESC SQL ordering keeps same-timestamp
            // events deterministic before pagination. The service must preserve that order.
            page.setRecords(java.util.List.of(
                    storedFeedback(703L, 503L, "NOT_HELPFUL", sharedCreatedAt),
                    storedFeedback(702L, 502L, "HELPFUL", sharedCreatedAt)
            ));
            page.setTotal(5L);
            return page;
        });

        PageResult<KnowledgeChatAnswerFeedbackResponse> result = knowledgeChatAnswerFeedbackService
                .listOwnedByKnowledgeBase(currentUser(), 201L, new PageRequest(2, 2));

        verify(knowledgeChatAnswerFeedbackMapper).selectPageOwnedByKnowledgeBase(
                feedbackPageCaptor.capture(),
                eq(201L),
                eq(101L)
        );
        assertThat(feedbackPageCaptor.getValue().getCurrent()).isEqualTo(2L);
        assertThat(feedbackPageCaptor.getValue().getSize()).isEqualTo(2L);
        assertThat(result.getItems()).extracting(KnowledgeChatAnswerFeedbackResponse::feedbackId)
                .containsExactly("703", "702");
        assertThat(result.getItems()).extracting(KnowledgeChatAnswerFeedbackResponse::answerId)
                .containsExactly("503", "502");
        assertThat(result.getItems()).extracting(KnowledgeChatAnswerFeedbackResponse::verdict)
                .containsExactly(
                        KnowledgeChatAnswerFeedbackVerdict.NOT_HELPFUL,
                        KnowledgeChatAnswerFeedbackVerdict.HELPFUL
                );
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(5L);
        assertThat(result.isHasNext()).isTrue();
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectStatusOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectOwnedByAnswerId(
                anyLong(),
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReturnTheSameEmptyV14PageForEmptyForeignAndWrongKnowledgeBaseScopes() {
        when(knowledgeChatAnswerFeedbackMapper.selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                anyLong(),
                anyLong()
        )).thenAnswer(invocation -> {
            Page<KnowledgeChatAnswerFeedback> page = invocation.getArgument(0);
            page.setRecords(java.util.List.of());
            page.setTotal(0L);
            return page;
        });

        PageResult<KnowledgeChatAnswerFeedbackResponse> empty = knowledgeChatAnswerFeedbackService
                .listOwnedByKnowledgeBase(currentUser(), 201L, new PageRequest(1, 20));
        PageResult<KnowledgeChatAnswerFeedbackResponse> wrongKnowledgeBase =
                knowledgeChatAnswerFeedbackService.listOwnedByKnowledgeBase(
                        currentUser(),
                        202L,
                        new PageRequest(1, 20)
                );
        PageResult<KnowledgeChatAnswerFeedbackResponse> foreignOwner =
                knowledgeChatAnswerFeedbackService.listOwnedByKnowledgeBase(
                        new AuthenticatedUser(102L, "other_owner", "Other", "USER"),
                        201L,
                        new PageRequest(1, 20)
                );

        assertEmptyPage(empty);
        assertEmptyPage(wrongKnowledgeBase);
        assertEmptyPage(foreignOwner);
        verify(knowledgeChatAnswerFeedbackMapper).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                eq(201L),
                eq(101L)
        );
        verify(knowledgeChatAnswerFeedbackMapper).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                eq(202L),
                eq(101L)
        );
        verify(knowledgeChatAnswerFeedbackMapper).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                eq(201L),
                eq(102L)
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
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

    private static KnowledgeChatAnswerFeedbackSummary summary(
            Long submittedCount,
            Long helpfulCount,
            Long notHelpfulCount
    ) {
        KnowledgeChatAnswerFeedbackSummary summary = new KnowledgeChatAnswerFeedbackSummary();
        summary.setSubmittedCount(submittedCount);
        summary.setHelpfulCount(helpfulCount);
        summary.setNotHelpfulCount(notHelpfulCount);
        return summary;
    }

    private static KnowledgeChatAnswerFeedbackCoverage coverage(
            Long answerCount,
            Long submittedCount,
            Long unsubmittedCount
    ) {
        KnowledgeChatAnswerFeedbackCoverage coverage = new KnowledgeChatAnswerFeedbackCoverage();
        coverage.setAnswerCount(answerCount);
        coverage.setSubmittedCount(submittedCount);
        coverage.setUnsubmittedCount(unsubmittedCount);
        return coverage;
    }

    private static void assertCoverage(
            KnowledgeChatAnswerFeedbackCoverageResponse coverage,
            long answerCount,
            long submittedCount,
            long unsubmittedCount
    ) {
        assertThat(coverage.answerCount()).isEqualTo(answerCount);
        assertThat(coverage.submittedCount()).isEqualTo(submittedCount);
        assertThat(coverage.unsubmittedCount()).isEqualTo(unsubmittedCount);
        assertThat(coverage.answerCount())
                .isEqualTo(Math.addExact(coverage.submittedCount(), coverage.unsubmittedCount()));
    }

    private static void assertSummary(
            KnowledgeChatAnswerFeedbackSummaryResponse summary,
            long submittedCount,
            long helpfulCount,
            long notHelpfulCount
    ) {
        assertThat(summary.submittedCount()).isEqualTo(submittedCount);
        assertThat(summary.helpfulCount()).isEqualTo(helpfulCount);
        assertThat(summary.notHelpfulCount()).isEqualTo(notHelpfulCount);
        assertThat(summary.submittedCount())
                .isEqualTo(Math.addExact(summary.helpfulCount(), summary.notHelpfulCount()));
    }

    private static KnowledgeChatAnswerFeedback storedFeedback(Long id, Long answerId, String verdict) {
        return storedFeedback(
                id,
                answerId,
                verdict,
                OffsetDateTime.parse("2026-08-27T10:30:00+08:00")
        );
    }

    private static KnowledgeChatAnswerFeedback storedFeedback(
            Long id,
            Long answerId,
            String verdict,
            OffsetDateTime createdAt
    ) {
        KnowledgeChatAnswerFeedback feedback = new KnowledgeChatAnswerFeedback();
        feedback.setId(id);
        feedback.setAnswerId(answerId);
        feedback.setVerdict(verdict);
        feedback.setCreatedAt(createdAt);
        return feedback;
    }

    private static void assertEmptyPage(PageResult<KnowledgeChatAnswerFeedbackResponse> page) {
        assertThat(page.getItems()).isEmpty();
        assertThat(page.getPage()).isOne();
        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.getTotal()).isZero();
        assertThat(page.isHasNext()).isFalse();
    }

    private static KnowledgeChatAnswerFeedbackStatus status(
            Long answerId,
            Long feedbackId,
            String verdict
    ) {
        KnowledgeChatAnswerFeedbackStatus status = new KnowledgeChatAnswerFeedbackStatus();
        status.setAnswerId(answerId);
        status.setFeedbackId(feedbackId);
        status.setVerdict(verdict);
        if (feedbackId != null) {
            status.setCreatedAt(OffsetDateTime.parse("2026-08-27T10:30:00+08:00"));
        }
        return status;
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
