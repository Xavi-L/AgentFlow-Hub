package com.agentflow.knowledge.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.KnowledgeRetrievalResponse;
import com.agentflow.knowledge.dto.RetrieveTestRequest;
import com.agentflow.knowledge.dto.RetrievedChunkResponse;
import com.agentflow.knowledge.service.KnowledgeRetrievalService;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeRetrievalControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldBindTheOwnerScopedRetrieveTestRoute() throws Exception {
        KnowledgeRetrievalService service = Mockito.mock(KnowledgeRetrievalService.class);
        AuthenticatedUser currentUser = currentUser();
        RetrieveTestRequest request = new RetrieveTestRequest("退款失败如何排查？", 3);
        when(service.retrieveTest(currentUser, 201L, request)).thenReturn(new KnowledgeRetrievalResponse(
                "退款失败如何排查？",
                3,
                List.of(new RetrievedChunkResponse(
                        1, 0.92, "401", "301", 0, "支付 / 退款", "先检查支付渠道错误码"
                ))
        ));
        authenticate(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/retrieve-test", 201L
                )
                        .header("X-Trace-Id", "af-test-retrieve")
                        .contentType("application/json")
                        .content("""
                                {"query":"退款失败如何排查？","topK":3}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].chunkId"
                ).value("401"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].rank"
                ).value(1));

        verify(service).retrieveTest(currentUser, 201L, request);
    }

    @Test
    void shouldRejectBlankQueriesBeforeCallingTheService() throws Exception {
        KnowledgeRetrievalService service = Mockito.mock(KnowledgeRetrievalService.class);
        authenticate(currentUser());

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/retrieve-test", 201L
                )
                        .header("X-Trace-Id", "af-test-retrieve-blank")
                        .contentType("application/json")
                        .content("""
                                {"query":"   ","topK":3}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldMapANonNumericKnowledgeBaseIdTo400InsteadOf500() throws Exception {
        KnowledgeRetrievalService service = Mockito.mock(KnowledgeRetrievalService.class);
        authenticate(currentUser());

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/not-a-number/retrieve-test"
                )
                        .header("X-Trace-Id", "af-test-retrieve-path")
                        .contentType("application/json")
                        .content("""
                                {"query":"退款规则"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));
    }

    private static MockMvc mockMvc(KnowledgeRetrievalService service) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeRetrievalController(service))
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
