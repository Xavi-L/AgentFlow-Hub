package com.agentflow.knowledge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.service.KnowledgeDocumentDeletionService;
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
 * 中文：V21 顶层详情与 V22 失败文档重试路由的 HTTP 外壳测试。它验证 JWT principal 与路径 ID 的传递、
 * 统一成功/404/409 响应，以及 JSON 中没有内部存储、owner、解析错误或 chunk 聚合字段。
 *
 * <p>English: HTTP-envelope tests for V21's top-level detail and V22's failed-document reprocess
 * routes. They verify forwarding of the JWT principal and path ID, the uniform success/404/409
 * responses, and absence of internal storage, owner, parser-error, and chunk-aggregation fields
 * from JSON.
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
    void shouldRequestFailedDocumentReprocessingWithOnlyTheSafeDocumentDto() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.reprocessOwnedFailed(eq(currentUser), eq(301L))).thenReturn(reprocessedResponse());
        authenticateAs(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/documents/{documentId}/reprocess",
                        301L
                ).header("X-Trace-Id", "af-test-document-reprocess"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Document reprocessing requested"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.id")
                        .value("301"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.knowledgeBaseId"
                ).value("201"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.parseStatus"
                ).value("PENDING"))
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

        verify(service).reprocessOwnedFailed(currentUser, 301L);
    }

    @Test
    void shouldKeepAnInvisibleDocumentAsTheUniform404WhenReprocessing() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.reprocessOwnedFailed(eq(currentUser), eq(301L))).thenThrow(
                new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found")
        );
        authenticateAs(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/documents/{documentId}/reprocess",
                        301L
                ).header("X-Trace-Id", "af-test-document-reprocess-not-found"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_NOT_FOUND"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Document not found"));

        verify(service).reprocessOwnedFailed(currentUser, 301L);
    }

    @Test
    void shouldExposeAVisibleNonFailedDocumentAsTheV22ConflictContract() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.reprocessOwnedFailed(eq(currentUser), eq(301L))).thenThrow(
                new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT)
        );
        authenticateAs(currentUser);

        mockMvc(service).perform(MockMvcRequestBuilders.post(
                        "/api/v1/documents/{documentId}/reprocess",
                        301L
                ).header("X-Trace-Id", "af-test-document-reprocess-conflict"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("KNOWLEDGE_DOCUMENT_REPROCESS_CONFLICT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Document is not eligible for reprocessing"));

        verify(service).reprocessOwnedFailed(currentUser, 301L);
    }

    @Test
    void shouldDeleteTheCurrentOwnersDocumentOnlyAfterTheV24ServiceCompletes() throws Exception {
        KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
        KnowledgeDocumentDeletionService deletionService = Mockito.mock(KnowledgeDocumentDeletionService.class);
        AuthenticatedUser currentUser = currentUser();
        authenticateAs(currentUser);

        mockMvc(documentService, deletionService).perform(MockMvcRequestBuilders.delete(
                        "/api/v1/documents/{documentId}",
                        301L
                ).header("X-Trace-Id", "af-test-document-delete"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Document deleted"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data")
                        .isEmpty());

        verify(deletionService).deleteOwned(currentUser, 301L);
        org.mockito.Mockito.verifyNoInteractions(documentService);
    }

    @Test
    void shouldMapV24DeletionConflictAndUnavailableErrorsWithoutChangingTheirCode() throws Exception {
        for (ErrorCode errorCode : List.of(
                ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_CONFLICT,
                ErrorCode.KNOWLEDGE_DOCUMENT_DELETION_UNAVAILABLE
        )) {
            KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
            KnowledgeDocumentDeletionService deletionService = Mockito.mock(KnowledgeDocumentDeletionService.class);
            AuthenticatedUser currentUser = currentUser();
            doThrow(new BusinessException(errorCode)).when(deletionService).deleteOwned(currentUser, 301L);
            authenticateAs(currentUser);

            int expectedStatus = errorCode.getHttpStatus();
            mockMvc(documentService, deletionService).perform(MockMvcRequestBuilders.delete(
                            "/api/v1/documents/{documentId}",
                            301L
                    ).header("X-Trace-Id", "af-test-document-delete-" + errorCode.getCode()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .is(expectedStatus))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                            .value(errorCode.getCode()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                            .value(errorCode.getMessage()));

            verify(deletionService).deleteOwned(currentUser, 301L);
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldKeepAnInvisibleDocumentAsTheUniform404WhenDeleting() throws Exception {
        KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
        KnowledgeDocumentDeletionService deletionService = Mockito.mock(KnowledgeDocumentDeletionService.class);
        AuthenticatedUser currentUser = currentUser();
        doThrow(new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Document not found"))
                .when(deletionService).deleteOwned(currentUser, 301L);
        authenticateAs(currentUser);

        mockMvc(documentService, deletionService).perform(MockMvcRequestBuilders.delete(
                        "/api/v1/documents/{documentId}",
                        301L
                ).header("X-Trace-Id", "af-test-document-delete-not-found"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_NOT_FOUND"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("Document not found"));

        verify(deletionService).deleteOwned(currentUser, 301L);
    }

    @Test
    void shouldMapANonNumericDeletePathTo400InsteadOfInvokingDeletion() throws Exception {
        KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
        KnowledgeDocumentDeletionService deletionService = Mockito.mock(KnowledgeDocumentDeletionService.class);
        authenticateAs(currentUser());

        mockMvc(documentService, deletionService).perform(MockMvcRequestBuilders.delete(
                        "/api/v1/documents/not-a-number"
                ).header("X-Trace-Id", "af-test-document-delete-path"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));

        org.mockito.Mockito.verifyNoInteractions(documentService, deletionService);
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

    private static KnowledgeDocumentResponse reprocessedResponse() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-29T12:30:00+08:00");
        return new KnowledgeDocumentResponse(
                "301",
                "201",
                "refund-rules.md",
                "MD",
                7L,
                "PENDING",
                OffsetDateTime.parse("2026-08-29T12:00:00+08:00"),
                now
        );
    }

    private static void authenticateAs(AuthenticatedUser currentUser) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );
    }

    private static MockMvc mockMvc(KnowledgeDocumentService service) {
        return mockMvc(service, Mockito.mock(KnowledgeDocumentDeletionService.class));
    }

    private static MockMvc mockMvc(
            KnowledgeDocumentService service,
            KnowledgeDocumentDeletionService deletionService
    ) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeDocumentDetailController(service, deletionService))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }
}
