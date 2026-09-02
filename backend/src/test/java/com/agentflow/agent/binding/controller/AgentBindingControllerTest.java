package com.agentflow.agent.binding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentflow.agent.binding.dto.AgentKnowledgeBindingsResponse;
import com.agentflow.agent.binding.dto.AgentToolBindingsResponse;
import com.agentflow.agent.binding.dto.ReplaceAgentKnowledgeBindingsRequest;
import com.agentflow.agent.binding.dto.ReplaceAgentToolBindingsRequest;
import com.agentflow.agent.binding.service.AgentBindingService;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentBindingControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReplaceKnowledgeBindingsFromStringIdsAndCurrentPrincipal() throws Exception {
        AgentBindingService service = Mockito.mock(AgentBindingService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.replaceKnowledgeBindings(
                eq(currentUser),
                eq(301L),
                any(ReplaceAgentKnowledgeBindingsRequest.class)
        )).thenReturn(new AgentKnowledgeBindingsResponse(List.of("201", "202")));

        mockMvc(service).perform(put("/api/v1/agents/301/knowledge-bases")
                        .contentType("application/json")
                        .content("{\"knowledgeBaseIds\":[\"201\",\"202\",\"201\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.knowledgeBaseIds[0]").value("201"))
                .andExpect(jsonPath("$.data.knowledgeBaseIds[1]").value("202"))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.priority").doesNotExist());

        ArgumentCaptor<ReplaceAgentKnowledgeBindingsRequest> captor = ArgumentCaptor.forClass(
                ReplaceAgentKnowledgeBindingsRequest.class
        );
        verify(service).replaceKnowledgeBindings(eq(currentUser), eq(301L), captor.capture());
        assertThat(captor.getValue().knowledgeBaseIds()).containsExactly(201L, 202L, 201L);
    }

    @Test
    void shouldReturnOrderedToolBindingIds() throws Exception {
        AgentBindingService service = Mockito.mock(AgentBindingService.class);
        AuthenticatedUser currentUser = authenticate();
        when(service.getToolBindings(currentUser, 301L)).thenReturn(
                new AgentToolBindingsResponse(List.of("280000000000000001", "270000000000000001"))
        );

        mockMvc(service).perform(get("/api/v1/agents/301/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolIds[0]").value("280000000000000001"))
                .andExpect(jsonPath("$.data.toolIds[1]").value("270000000000000001"));

        verify(service).getToolBindings(currentUser, 301L);
    }

    @Test
    void shouldRejectNumericIdsUnknownFieldsAndClientOwnedToolMetadata() throws Exception {
        AgentBindingService service = Mockito.mock(AgentBindingService.class);
        authenticate();
        List<String> invalidKnowledgeBodies = List.of(
                "{\"knowledgeBaseIds\":[201]}",
                "{\"knowledgeBaseIds\":[\"201\"],\"userId\":\"101\"}",
                "{\"knowledgeBaseIds\":null}"
        );
        for (String body : invalidKnowledgeBodies) {
            mockMvc(service).perform(put("/api/v1/agents/301/knowledge-bases")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));
        }
        mockMvc(service).perform(put("/api/v1/agents/301/tools")
                        .contentType("application/json")
                        .content("""
                                {
                                  "toolIds":["270000000000000001"],
                                  "enabled":true,
                                  "configOverride":{}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));

        verifyNoInteractions(service);
    }

    private static MockMvc mockMvc(AgentBindingService service) {
        return MockMvcBuilders.standaloneSetup(new AgentBindingController(service))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static AuthenticatedUser authenticate() {
        AuthenticatedUser currentUser = new AuthenticatedUser(101L, "owner", "Owner", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );
        return currentUser;
    }
}
