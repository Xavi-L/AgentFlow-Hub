package com.agentflow.knowledge.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.KnowledgeContextResponse;
import com.agentflow.knowledge.dto.KnowledgeContextSourceResponse;
import com.agentflow.knowledge.dto.RetrieveContextTestRequest;
import com.agentflow.knowledge.service.KnowledgeContextService;
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

class KnowledgeContextControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldBindTheOwnerScopedRetrieveContextTestRoute() throws Exception {
        KnowledgeContextService service = Mockito.mock(KnowledgeContextService.class);
        AuthenticatedUser currentUser = currentUser();
        RetrieveContextTestRequest request = new RetrieveContextTestRequest("退款失败如何排查？", 3, 800);
        when(service.retrieveContextTest(currentUser, 201L, request)).thenReturn(new KnowledgeContextResponse(
                "退款失败如何排查？",
                3,
                800,
                36,
                1,
                "[S1]\\nSource: refund-rules.md\\nContent:\\n先检查支付渠道错误码",
                List.of(new KnowledgeContextSourceResponse(
                        "S1",
                        "401",
                        "301",
                        "refund-rules.md",
                        "支付 / 退款",
                        0.92
                ))
        ));
        authenticate(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/retrieve-context-test", 201L
                )
                        .header("X-Trace-Id", "af-test-retrieve-context")
                        .contentType("application/json")
                        .content("""
                                {"query":"退款失败如何排查？","topK":3,"maxContextTokens":800}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.context"
                ).value("[S1]\\nSource: refund-rules.md\\nContent:\\n先检查支付渠道错误码"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.sources[0].citationId"
                ).value("S1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.sources[0].fileName"
                ).value("refund-rules.md"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.skippedChunkCount"
                ).value(1));

        verify(service).retrieveContextTest(currentUser, 201L, request);
    }

    @Test
    void shouldRejectAnOutOfRangeContextBudgetBeforeCallingTheService() throws Exception {
        KnowledgeContextService service = Mockito.mock(KnowledgeContextService.class);
        authenticate(currentUser());

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/retrieve-context-test", 201L
                )
                        .header("X-Trace-Id", "af-test-retrieve-context-invalid-budget")
                        .contentType("application/json")
                        .content("""
                                {"query":"退款规则","topK":3,"maxContextTokens":0}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));

        verifyNoInteractions(service);
    }

    private static MockMvc mockMvc(KnowledgeContextService service) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeContextController(service))
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
