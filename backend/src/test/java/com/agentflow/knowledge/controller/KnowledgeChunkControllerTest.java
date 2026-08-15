package com.agentflow.knowledge.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.ChunkVectorizationResponse;
import com.agentflow.knowledge.service.ChunkVectorizationService;
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

/** Verifies the owner-principal hand-off and exact V5 explicit route. */
class KnowledgeChunkControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldBindTheExplicitPendingVectorizationRoute() throws Exception {
        ChunkVectorizationService service = Mockito.mock(ChunkVectorizationService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.vectorizePending(currentUser, 201L)).thenReturn(
                new ChunkVectorizationResponse(2, 1, 1, 0, 1)
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/chunks/vectorize-pending", 201L
                ).header("X-Trace-Id", "af-test-chunk-vectorize"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.completed"
                ).value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.skipped"
                ).value(1));

        verify(service).vectorizePending(currentUser, 201L);
    }

    @Test
    void shouldMapANonNumericKnowledgeBaseIdTo400InsteadOf500() throws Exception {
        ChunkVectorizationService service = Mockito.mock(ChunkVectorizationService.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser(), "test", List.of())
        );

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/not-a-number/chunks/vectorize-pending"
                ).header("X-Trace-Id", "af-test-vectorize-path"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));
    }

    private static MockMvc mockMvc(ChunkVectorizationService service) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeChunkController(service))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
