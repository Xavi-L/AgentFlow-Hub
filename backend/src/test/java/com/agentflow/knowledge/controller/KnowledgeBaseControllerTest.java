package com.agentflow.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.CreateKnowledgeBaseRequest;
import com.agentflow.knowledge.dto.KnowledgeBaseResponse;
import com.agentflow.knowledge.service.KnowledgeBaseService;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 中文：Controller 的轻量测试验证统一响应外壳、201 状态码以及 principal 到 Service 的传递。
 * Spring Security 过滤器本身已有独立测试，避免这里重复搭建整个安全链。
 *
 * <p>English: Lightweight controller tests verify the shared envelope, 201 status, and
 * principal-to-service handoff. The Spring Security filter already has dedicated tests,
 * so this class avoids rebuilding the full security chain.
 */
class KnowledgeBaseControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateThroughTheUnified201Response() {
        KnowledgeBaseService service = Mockito.mock(KnowledgeBaseService.class);
        KnowledgeBaseController controller = new KnowledgeBaseController(service);
        AuthenticatedUser currentUser = currentUser();
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest(
                "Payment knowledge base", null, null, null, null, null
        );
        when(service.create(currentUser, request)).thenReturn(response("201", "Payment knowledge base"));

        ResponseEntity<ApiResponse<KnowledgeBaseResponse>> httpResponse = controller.create(
                currentUser,
                request
        );

        assertThat(httpResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(httpResponse.getBody()).isNotNull();
        assertThat(httpResponse.getBody().getCode()).isEqualTo("OK");
        assertThat(httpResponse.getBody().getMessage()).isEqualTo("Knowledge base created");
        assertThat(httpResponse.getBody().getData().id()).isEqualTo("201");
        verify(service).create(currentUser, request);
    }

    @Test
    void shouldReturnTheUnifiedPageForTheCurrentUser() {
        KnowledgeBaseService service = Mockito.mock(KnowledgeBaseService.class);
        KnowledgeBaseController controller = new KnowledgeBaseController(service);
        AuthenticatedUser currentUser = currentUser();
        PageRequest pageRequest = new PageRequest(2, 5);
        PageResult<KnowledgeBaseResponse> page = PageResult.of(
                List.of(response("201", "Payment knowledge base")), 2, 5, 6
        );
        when(service.listOwnedBy(currentUser, pageRequest)).thenReturn(page);

        ApiResponse<PageResult<KnowledgeBaseResponse>> response = controller.list(currentUser, pageRequest);

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getData()).isSameAs(page);
        assertThat(response.getData().getItems()).extracting(KnowledgeBaseResponse::id)
                .containsExactly("201");
        verify(service).listOwnedBy(currentUser, pageRequest);
    }

    @Test
    void shouldBindTheCurrentUserToTheKnowledgeBaseDetailRoute() throws Exception {
        KnowledgeBaseService service = Mockito.mock(KnowledgeBaseService.class);
        AuthenticatedUser currentUser = currentUser();
        KnowledgeBaseResponse knowledgeBase = response("201", "Payment knowledge base");
        when(service.getOwnedById(eq(currentUser), eq(201L))).thenReturn(knowledgeBase);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );

        mockMvc(service).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}", 201L
                ).header("X-Trace-Id", "af-test-kb-detail"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Knowledge base retrieved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.id")
                        .value("201"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.userId")
                        .doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.deletedAt")
                        .doesNotExist());

        verify(service).getOwnedById(currentUser, 201L);
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeBaseResponse response(String id, String name) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-06T12:00:00+08:00");
        return new KnowledgeBaseResponse(
                id,
                name,
                null,
                "dashscope",
                "text-embedding-v4",
                800,
                120,
                "ACTIVE",
                now,
                now
        );
    }

    private static MockMvc mockMvc(KnowledgeBaseService service) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeBaseController(service))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }
}
