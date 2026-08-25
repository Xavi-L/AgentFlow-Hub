package com.agentflow.knowledge.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatResponse;
import com.agentflow.knowledge.dto.KnowledgeContextSourceResponse;
import com.agentflow.knowledge.service.KnowledgeChatService;
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

class KnowledgeChatControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldBindTheSingleTurnOwnerScopedChatRoute() throws Exception {
        KnowledgeChatService service = Mockito.mock(KnowledgeChatService.class);
        AuthenticatedUser currentUser = currentUser();
        ChatTestRequest request = new ChatTestRequest("退款失败如何排查？", 3, 800, 256);
        when(service.chatTest(currentUser, 201L, request)).thenReturn(response());
        authenticate(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-test", 201L
                )
                        .header("X-Trace-Id", "af-test-chat-001")
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
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.answer")
                        .value("先检查支付渠道错误码。[S1]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.maxContextTokens"
                ).value(800))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.usedContextTokens"
                ).value(36))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.maxAnswerTokens"
                ).value(256))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.sources[0].citationId"
                ).value("S1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.citationIds[0]"
                ).value("S1"));

        verify(service).chatTest(currentUser, 201L, request);
    }

    @Test
    void shouldRejectAnOutOfRangeAnswerBudgetBeforeCallingTheService() throws Exception {
        KnowledgeChatService service = Mockito.mock(KnowledgeChatService.class);
        authenticate(currentUser());

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-test", 201L
                )
                        .header("X-Trace-Id", "af-test-chat-invalid-budget")
                        .contentType("application/json")
                        .content("""
                                {"query":"退款规则","topK":3,"maxContextTokens":800,"maxAnswerTokens":4097}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));

        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectEveryForbiddenClientControlFieldBeforeCallingTheService() throws Exception {
        KnowledgeChatService service = Mockito.mock(KnowledgeChatService.class);
        authenticate(currentUser());

        for (String forbiddenField : List.of("model", "prompt", "chunkId", "citationId")) {
            mockMvc(service).perform(MockMvcRequestBuilders.post(
                            "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-test", 201L
                    )
                            .header("X-Trace-Id", "af-test-chat-forbidden-" + forbiddenField)
                            .contentType("application/json")
                            .content("""
                                    {
                                      "query":"退款规则",
                                      "topK":3,
                                      "maxContextTokens":800,
                                      "maxAnswerTokens":256,
                                      "%s":"client must not control this"
                                    }
                                    """.formatted(forbiddenField)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                            .value("COMMON_REQUEST_BODY_INVALID"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void shouldExposeTheThreeV9ControlledFailuresWithTheirStableHttpContracts() throws Exception {
        KnowledgeChatService service = Mockito.mock(KnowledgeChatService.class);
        AuthenticatedUser currentUser = currentUser();
        ChatTestRequest request = new ChatTestRequest("退款规则", 3, 800, 256);
        authenticate(currentUser);

        for (ErrorCode errorCode : List.of(
                ErrorCode.KNOWLEDGE_CONTEXT_EMPTY,
                ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID,
                ErrorCode.KNOWLEDGE_CHAT_GATEWAY_UNAVAILABLE
        )) {
            doThrow(new BusinessException(errorCode)).when(service).chatTest(currentUser, 201L, request);

            mockMvc(service).perform(MockMvcRequestBuilders.post(
                            "/api/v1/knowledge-bases/{knowledgeBaseId}/chat-test", 201L
                    )
                            .header("X-Trace-Id", "af-test-chat-" + errorCode.getCode())
                            .contentType("application/json")
                            .content("""
                                    {"query":"退款规则","topK":3,"maxContextTokens":800,"maxAnswerTokens":256}
                                    """))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .is(errorCode.getHttpStatus()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                            .value(errorCode.getCode()));
        }
    }

    private static KnowledgeChatResponse response() {
        return new KnowledgeChatResponse(
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
                List.of("S1")
        );
    }

    private static MockMvc mockMvc(KnowledgeChatService service) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeChatController(service))
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
