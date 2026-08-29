package com.agentflow.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackCoverageItemResponse;
import com.agentflow.knowledge.service.KnowledgeChatAnswerFeedbackService;
import com.agentflow.knowledge.service.KnowledgeChatAnswerService;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeChatAnswerFeedbackCoverageLedgerControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldExposeOnlyThreeV17CoverageLedgerFieldsThroughTheNestedPagedRoute() throws Exception {
        KnowledgeChatAnswerService answerService = Mockito.mock(KnowledgeChatAnswerService.class);
        KnowledgeChatAnswerFeedbackService feedbackService = Mockito.mock(
                KnowledgeChatAnswerFeedbackService.class
        );
        AuthenticatedUser currentUser = currentUser();
        PageResult<KnowledgeChatAnswerFeedbackCoverageItemResponse> page = PageResult.of(
                List.of(
                        new KnowledgeChatAnswerFeedbackCoverageItemResponse(
                                "503",
                                true,
                                OffsetDateTime.parse("2026-08-29T11:00:00+08:00")
                        ),
                        new KnowledgeChatAnswerFeedbackCoverageItemResponse(
                                "502",
                                false,
                                OffsetDateTime.parse("2026-08-29T10:00:00+08:00")
                        )
                ),
                2,
                5,
                6
        );
        when(feedbackService.listCoverageLedgerOwnedByKnowledgeBase(
                eq(currentUser),
                eq(201L),
                any(PageRequest.class)
        )).thenReturn(page);
        authenticate(currentUser);

        mockMvc(answerService, feedbackService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-answer-feedbacks/coverage-ledger",
                        201L
                )
                        .param("page", "2")
                        .param("pageSize", "5")
                        .header("X-Trace-Id", "af-test-v17-feedback-coverage-ledger-001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Knowledge chat answer feedback coverage ledger retrieved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.page"
                ).value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.pageSize"
                ).value(5))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.total"
                ).value(6))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.hasNext"
                ).value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0]",
                        org.hamcrest.Matchers.aMapWithSize(3)
                ))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].answerId"
                ).value("503"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].submitted"
                ).value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].answerCreatedAt"
                ).exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].feedbackId"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].verdict"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].createdAt"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].answer"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].query"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].citationIds"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].feedback"
                ).doesNotExist());

        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(feedbackService).listCoverageLedgerOwnedByKnowledgeBase(
                eq(currentUser),
                eq(201L),
                pageRequestCaptor.capture()
        );
        assertThat(pageRequestCaptor.getValue().getPage()).isEqualTo(2);
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(5);
        verifyNoInteractions(answerService);
    }

    @Test
    void shouldReturnTheV17EmptyPageShapeWithoutTouchingTheAnswerService() throws Exception {
        KnowledgeChatAnswerService answerService = Mockito.mock(KnowledgeChatAnswerService.class);
        KnowledgeChatAnswerFeedbackService feedbackService = Mockito.mock(
                KnowledgeChatAnswerFeedbackService.class
        );
        AuthenticatedUser currentUser = currentUser();
        when(feedbackService.listCoverageLedgerOwnedByKnowledgeBase(
                eq(currentUser),
                eq(999L),
                any(PageRequest.class)
        )).thenReturn(PageResult.of(List.of(), 1, 20, 0));
        authenticate(currentUser);

        mockMvc(answerService, feedbackService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-answer-feedbacks/coverage-ledger",
                        999L
                )
                        .header("X-Trace-Id", "af-test-v17-feedback-coverage-ledger-empty-001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.page"
                ).value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.pageSize"
                ).value(20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.total"
                ).value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items"
                ).isEmpty());

        verify(feedbackService).listCoverageLedgerOwnedByKnowledgeBase(
                eq(currentUser),
                eq(999L),
                any(PageRequest.class)
        );
        verifyNoInteractions(answerService);
    }

    private static MockMvc mockMvc(
            KnowledgeChatAnswerService answerService,
            KnowledgeChatAnswerFeedbackService feedbackService
    ) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeChatAnswerController(answerService, feedbackService))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static void authenticate(AuthenticatedUser currentUser) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
