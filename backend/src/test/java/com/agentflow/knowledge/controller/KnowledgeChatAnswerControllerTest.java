package com.agentflow.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerSummaryResponse;
import com.agentflow.knowledge.dto.KnowledgeContextSourceResponse;
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

class KnowledgeChatAnswerControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldBindTheV10OwnerScopedChatRouteAndReturnTheAnswerId() throws Exception {
        KnowledgeChatAnswerService service = Mockito.mock(KnowledgeChatAnswerService.class);
        AuthenticatedUser currentUser = currentUser();
        ChatTestRequest request = request();
        when(service.chat(currentUser, 201L, request)).thenReturn(response());
        authenticate(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat", 201L
                )
                        .header("X-Trace-Id", "af-test-v10-chat-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "query":"退款失败如何排查？",
                                  "topK":3,
                                  "maxContextTokens":800,
                                  "maxAnswerTokens":256
                                }
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.answerId"
                ).value("501"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.sources[0].citationId"
                ).value("S1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.citationIds[0]"
                ).value("S1"));

        verify(service).chat(currentUser, 201L, request);
    }

    @Test
    void shouldReadAnOwnedAnswerThroughTheNestedAuditRoute() throws Exception {
        KnowledgeChatAnswerService service = Mockito.mock(KnowledgeChatAnswerService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.getById(currentUser, 201L, 501L)).thenReturn(response());
        authenticate(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers/{answerId}",
                        201L,
                        501L
                )
                        .header("X-Trace-Id", "af-test-v10-answer-read-001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.answer"
                ).value("先检查支付渠道错误码。[S1]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.maxContextTokens"
                ).value(800))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.createdAt"
                ).exists());

        verify(service).getById(currentUser, 201L, 501L);
    }

    @Test
    void shouldListOnlySummaryFieldsThroughTheOwnerScopedAuditLedgerRoute() throws Exception {
        KnowledgeChatAnswerService service = Mockito.mock(KnowledgeChatAnswerService.class);
        AuthenticatedUser currentUser = currentUser();
        PageResult<KnowledgeChatAnswerSummaryResponse> page = PageResult.of(
                List.of(new KnowledgeChatAnswerSummaryResponse(
                        "501",
                        "退款失败如何排查？",
                        List.of("S1"),
                        OffsetDateTime.parse("2026-08-25T10:30:00+08:00")
                )),
                2,
                5,
                6
        );
        when(service.listOwnedByKnowledgeBase(eq(currentUser), eq(201L), any(PageRequest.class)))
                .thenReturn(page);
        authenticate(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers", 201L
                )
                        .param("page", "2")
                        .param("pageSize", "5")
                        .header("X-Trace-Id", "af-test-v11-answer-list-001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
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
                        "$.data.items[0].answerId"
                ).value("501"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].query"
                ).value("退款失败如何排查？"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].citationIds[0]"
                ).value("S1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].createdAt"
                ).exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].answer"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].sources"
                ).doesNotExist());

        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).listOwnedByKnowledgeBase(
                eq(currentUser),
                eq(201L),
                pageRequestCaptor.capture()
        );
        assertThat(pageRequestCaptor.getValue().getPage()).isEqualTo(2);
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void shouldReuseTheStrictFourFieldChatRequestBeforeCallingTheAuditService() throws Exception {
        KnowledgeChatAnswerService service = Mockito.mock(KnowledgeChatAnswerService.class);
        authenticate(currentUser());

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat", 201L
                )
                        .header("X-Trace-Id", "af-test-v10-forbidden-client-control")
                        .contentType("application/json")
                        .content("""
                                {
                                  "query":"退款规则",
                                  "topK":3,
                                  "maxContextTokens":800,
                                  "maxAnswerTokens":256,
                                  "model":"client-must-not-control-model"
                                }
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_REQUEST_BODY_INVALID"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldExposeTheSameNotFoundContractForAnUnownedAnswer() throws Exception {
        KnowledgeChatAnswerService service = Mockito.mock(KnowledgeChatAnswerService.class);
        AuthenticatedUser currentUser = currentUser();
        doThrow(new BusinessException(ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND))
                .when(service)
                .getById(currentUser, 201L, 501L);
        authenticate(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-answers/{answerId}",
                        201L,
                        501L
                )
                        .header("X-Trace-Id", "af-test-v10-answer-not-found-001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("KNOWLEDGE_CHAT_ANSWER_NOT_FOUND"));
    }

    private static ChatTestRequest request() {
        return new ChatTestRequest("退款失败如何排查？", 3, 800, 256);
    }

    private static KnowledgeChatAnswerResponse response() {
        return new KnowledgeChatAnswerResponse(
                "501",
                "先检查支付渠道错误码。[S1]",
                "退款失败如何排查？",
                3,
                800,
                36,
                1,
                256,
                List.of(new KnowledgeContextSourceResponse(
                        "S1",
                        "401",
                        "301",
                        "refund-rules.md",
                        "支付 / 退款",
                        0.92
                )),
                List.of("S1"),
                OffsetDateTime.parse("2026-08-25T10:30:00+08:00")
        );
    }

    private static MockMvc mockMvc(KnowledgeChatAnswerService service) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeChatAnswerController(service))
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
