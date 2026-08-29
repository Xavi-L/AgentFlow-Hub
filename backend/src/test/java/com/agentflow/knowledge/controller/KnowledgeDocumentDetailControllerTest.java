package com.agentflow.knowledge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.service.KnowledgeDocumentService;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
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

/**
 * 中文：V21 顶层详情路由的 HTTP 外壳测试。它验证 JWT principal 与路径 ID 的传递、统一成功/
 * 404 响应，以及 JSON 中没有内部存储、owner、解析错误或 chunk 聚合字段。
 *
 * <p>English: HTTP-envelope tests for V21's top-level detail route. They verify forwarding
 * of the JWT principal and path ID, the uniform success/404 responses, and absence of internal
 * storage, owner, parser-error, and chunk-aggregation fields from JSON.
 */
class KnowledgeDocumentDetailControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnTheSafeDocumentDetailForTheCurrentOwner() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.getOwnedById(eq(currentUser), eq(301L))).thenReturn(response());
        authenticateAs(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.get("/api/v1/documents/{documentId}", 301L)
                        .header("X-Trace-Id", "af-test-document-detail"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Document retrieved"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.id")
                        .value("301"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.knowledgeBaseId"
                ).value("201"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.fileName"
                ).value("refund-rules.md"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.fileType"
                ).value("MD"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.fileSize"
                ).value(7))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.parseStatus"
                ).value("FAILED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.storageBucket"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.storageObjectKey"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.userId"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.parseError"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.mimeType"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.deletedAt"
                ).doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.chunkCount"
                ).doesNotExist());

        verify(service).getOwnedById(currentUser, 301L);
    }

    @Test
    void shouldKeepAnInvisibleDocumentAsTheUniform404Response() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.getOwnedById(eq(currentUser), eq(301L))).thenThrow(
                new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found")
        );
        authenticateAs(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.get("/api/v1/documents/{documentId}", 301L)
                        .header("X-Trace-Id", "af-test-document-detail-not-found"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_NOT_FOUND"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Document not found"));

        verify(service).getOwnedById(currentUser, 301L);
    }

    @Test
    void shouldMapANonNumericDocumentIdTo400InsteadOf500() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        authenticateAs(currentUser());

        mockMvc(service).perform(MockMvcRequestBuilders.get("/api/v1/documents/not-a-number")
                        .header("X-Trace-Id", "af-test-document-detail-path"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeDocumentResponse response() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-29T12:00:00+08:00");
        return new KnowledgeDocumentResponse(
                "301",
                "201",
                "refund-rules.md",
                "MD",
                7L,
                "FAILED",
                now,
                now
        );
    }

    private static void authenticateAs(AuthenticatedUser currentUser) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );
    }

    private static MockMvc mockMvc(KnowledgeDocumentService service) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeDocumentDetailController(service))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }
}
