package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackCoverageItemResponse;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackCoverageItem;
import com.agentflow.knowledge.repository.KnowledgeChatAnswerFeedbackMapper;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeChatAnswerFeedbackCoverageLedgerServiceTest {

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
    void shouldListSubmittedAndUnsubmittedAnswersInStableAnswerOrderWithoutWriting() {
        OffsetDateTime newest = OffsetDateTime.parse("2026-08-29T11:00:00+08:00");
        OffsetDateTime older = OffsetDateTime.parse("2026-08-29T10:00:00+08:00");
        when(knowledgeChatAnswerFeedbackMapper.selectCoverageLedgerPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedbackCoverageItem>>any(),
                eq(201L),
                eq(101L)
        )).thenAnswer(invocation -> {
            Page<KnowledgeChatAnswerFeedbackCoverageItem> page = invocation.getArgument(0);
            page.setRecords(List.of(
                    item(503L, true, newest),
                    item(502L, false, older)
            ));
            page.setTotal(5L);
            return page;
        });

        PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse> result =
                knowledgeChatAnswerFeedbackService.listCoverageLedgerOwnedByKnowledgeBase(
                        currentUser(),
                        201L,
                        new PageRequest(2, 2)
                );

        ArgumentCaptor<Page<KnowledgeChatAnswerFeedbackCoverageItem>> pageCaptor =
                ArgumentCaptor.forClass(Page.class);
        verify(knowledgeChatAnswerFeedbackMapper).selectCoverageLedgerPageOwnedByKnowledgeBase(
                pageCaptor.capture(),
                eq(201L),
                eq(101L)
        );
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(2L);
        assertThat(result.getItems()).extracting(
                        KnowledgeChatAnswerFeedbackCoverageItemResponse::answerId
                )
                .containsExactly("503", "502");
        assertThat(result.getItems()).extracting(
                        KnowledgeChatAnswerFeedbackCoverageItemResponse::submitted
                )
                .containsExactly(true, false);
        assertThat(result.getItems()).extracting(
                        KnowledgeChatAnswerFeedbackCoverageItemResponse::answerCreatedAt
                )
                .containsExactly(newest, older);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(5L);
        assertThat(result.isHasNext()).isTrue();

        verify(knowledgeChatAnswerFeedbackMapper, never()).insertIfAbsent(
                org.mockito.ArgumentMatchers.any(KnowledgeChatAnswerFeedback.class),
                anyLong(),
                anyLong()
        );
    }

    @Test
    void shouldReturnAnEmptyPageForEmptyForeignAndWrongKnowledgeBaseScopesWithoutPrechecks() {
        when(knowledgeChatAnswerFeedbackMapper.selectCoverageLedgerPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedbackCoverageItem>>any(),
                anyLong(),
                anyLong()
        )).thenAnswer(invocation -> {
            Page<KnowledgeChatAnswerFeedbackCoverageItem> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0L);
            return page;
        });

        PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse> empty =
                knowledgeChatAnswerFeedbackService.listCoverageLedgerOwnedByKnowledgeBase(
                        currentUser(),
                        201L,
                        new PageRequest(1, 20)
                );
        PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse> wrongKnowledgeBase =
                knowledgeChatAnswerFeedbackService.listCoverageLedgerOwnedByKnowledgeBase(
                        currentUser(),
                        202L,
                        new PageRequest(1, 20)
                );
        PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse> foreignOwner =
                knowledgeChatAnswerFeedbackService.listCoverageLedgerOwnedByKnowledgeBase(
                        new AuthenticatedUser(102L, "other_owner", "Other", "USER"),
                        201L,
                        new PageRequest(1, 20)
                );

        assertEmptyPage(empty);
        assertEmptyPage(wrongKnowledgeBase);
        assertEmptyPage(foreignOwner);
        verify(knowledgeChatAnswerFeedbackMapper).selectCoverageLedgerPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedbackCoverageItem>>any(),
                eq(201L),
                eq(101L)
        );
        verify(knowledgeChatAnswerFeedbackMapper).selectCoverageLedgerPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedbackCoverageItem>>any(),
                eq(202L),
                eq(101L)
        );
        verify(knowledgeChatAnswerFeedbackMapper).selectCoverageLedgerPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedbackCoverageItem>>any(),
                eq(201L),
                eq(102L)
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectCoverageOwnedByKnowledgeBase(
                anyLong(),
                anyLong()
        );
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectSummaryOwnedByKnowledgeBase(
                anyLong(),
                anyLong()
        );
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
        verify(knowledgeChatAnswerFeedbackMapper, never()).selectPageOwnedByKnowledgeBase(
                org.mockito.ArgumentMatchers.<Page<KnowledgeChatAnswerFeedback>>any(),
                anyLong(),
                anyLong()
        );
    }

    private static KnowledgeChatAnswerFeedbackCoverageItem item(
            Long answerId,
            Boolean submitted,
            OffsetDateTime answerCreatedAt
    ) {
        KnowledgeChatAnswerFeedbackCoverageItem item = new KnowledgeChatAnswerFeedbackCoverageItem();
        item.setAnswerId(answerId);
        item.setSubmitted(submitted);
        item.setAnswerCreatedAt(answerCreatedAt);
        return item;
    }

    private static void assertEmptyPage(
            PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse> page
    ) {
        assertThat(page.getItems()).isEmpty();
        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.getTotal()).isZero();
        assertThat(page.isHasNext()).isFalse();
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
