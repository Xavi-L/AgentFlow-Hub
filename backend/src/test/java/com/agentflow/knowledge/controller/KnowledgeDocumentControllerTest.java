package com.agentflow.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.knowledge.dto.DocumentProcessingResponse;
import com.agentflow.knowledge.dto.KnowledgeChunkResponse;
import com.agentflow.knowledge.dto.KnowledgeDocumentResponse;
import com.agentflow.knowledge.service.DocumentProcessingService;
import com.agentflow.knowledge.service.KnowledgeDocumentService;
import com.agentflow.user.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 中文：Controller 轻量测试验证上传、列表、V4 处理/查看的统一 HTTP 外壳和当前 principal 的透传。
 * 文件格式、owner 查询、落盘补偿和 chunk 事务都属于 Service 的独立测试。
 *
 * <p>English: Lightweight controller tests verify the common HTTP envelope and
 * hand-off of the current principal for upload, list, V4 processing, and chunk reads.
 * File validation, owner lookup, storage compensation, and chunk transactions have
 * dedicated service tests.
 */
class KnowledgeDocumentControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldBindAMultipartFileToTheNestedUploadRoute() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        AuthenticatedUser currentUser = currentUser();
        MockMultipartFile file = new MockMultipartFile(
                "file", "refund-rules.md", "text/markdown",
                "# rules".getBytes(StandardCharsets.UTF_8)
        );
        when(service.upload(eq(currentUser), eq(201L), eq(file)))
                .thenReturn(response("301", "201", "refund-rules.md", "MD"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(MockMvcRequestBuilders.multipart(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/documents", 201L
                ).file(file).header("X-Trace-Id", "af-test-document-upload"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.parseStatus"
                ).value("PENDING"));

        verify(service).upload(currentUser, 201L, file);
    }

    @Test
    void shouldMapANonNumericKnowledgeBaseIdTo400InsteadOf500() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser(), "test", List.of())
        );

        mockMvc(service).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/not-a-number/documents"
                ).header("X-Trace-Id", "af-test-document-path"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("COMMON_PARAM_INVALID"));
    }

    @Test
    void shouldBindAMissingFilePartAsTheStableRequiredFileError() throws Exception {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        AuthenticatedUser currentUser = currentUser();
        when(service.upload(eq(currentUser), eq(201L), isNull())).thenThrow(
                new BusinessException(ErrorCode.KNOWLEDGE_DOCUMENT_FILE_REQUIRED)
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );

        mockMvc(service).perform(MockMvcRequestBuilders.multipart(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/documents", 201L
                ).header("X-Trace-Id", "af-test-document-missing-file"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("KNOWLEDGE_DOCUMENT_FILE_REQUIRED"));

        verify(service).upload(currentUser, 201L, null);
    }

    @Test
    void shouldReturnTheUnified201ResponseForAnUpload() {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        KnowledgeDocumentController controller = controller(service);
        AuthenticatedUser currentUser = currentUser();
        MockMultipartFile file = new MockMultipartFile(
                "file", "refund-rules.md", "text/markdown",
                "# rules".getBytes(StandardCharsets.UTF_8)
        );
        KnowledgeDocumentResponse document = response("301", "201", "refund-rules.md", "MD");
        when(service.upload(currentUser, 201L, file)).thenReturn(document);

        ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> httpResponse = controller.upload(
                currentUser,
                201L,
                file
        );

        assertThat(httpResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(httpResponse.getBody()).isNotNull();
        assertThat(httpResponse.getBody().getCode()).isEqualTo("OK");
        assertThat(httpResponse.getBody().getMessage()).isEqualTo("Document uploaded");
        assertThat(httpResponse.getBody().getData().id()).isEqualTo("301");
        assertThat(httpResponse.getBody().getData().parseStatus()).isEqualTo("PENDING");
        verify(service).upload(currentUser, 201L, file);
    }

    @Test
    void shouldReturnAUnifiedPageForTheCurrentUsersKnowledgeBase() {
        KnowledgeDocumentService service = Mockito.mock(KnowledgeDocumentService.class);
        KnowledgeDocumentController controller = controller(service);
        AuthenticatedUser currentUser = currentUser();
        PageRequest pageRequest = new PageRequest(2, 5);
        PageResult<KnowledgeDocumentResponse> page = PageResult.of(
                List.of(response("301", "201", "refund-rules.md", "MD")),
                2,
                5,
                6
        );
        when(service.listOwnedByKnowledgeBase(currentUser, 201L, pageRequest)).thenReturn(page);

        ApiResponse<PageResult<KnowledgeDocumentResponse>> response = controller.list(
                currentUser,
                201L,
                pageRequest
        );

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getData()).isSameAs(page);
        assertThat(response.getData().getItems()).extracting(KnowledgeDocumentResponse::fileName)
                .containsExactly("refund-rules.md");
        verify(service).listOwnedByKnowledgeBase(currentUser, 201L, pageRequest);
    }

    @Test
    void shouldBindTheExplicitPendingProcessingRoute() throws Exception {
        KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
        DocumentProcessingService processingService = Mockito.mock(DocumentProcessingService.class);
        AuthenticatedUser currentUser = currentUser();
        when(processingService.processPending(currentUser, 201L)).thenReturn(
                new DocumentProcessingResponse(2, 2, 1, 1, 0)
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );

        mockMvc(documentService, processingService).perform(MockMvcRequestBuilders.post(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/documents/process-pending", 201L
                ).header("X-Trace-Id", "af-test-document-process"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("OK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.completed"
                ).value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.failed"
                ).value(1));

        verify(processingService).processPending(currentUser, 201L);
    }

    @Test
    void shouldBindTheOwnerScopedChunkListRoute() throws Exception {
        KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
        DocumentProcessingService processingService = Mockito.mock(DocumentProcessingService.class);
        AuthenticatedUser currentUser = currentUser();
        PageResult<KnowledgeChunkResponse> chunks = PageResult.of(
                List.of(chunkResponse("401", "301", 0, "Refund rules")),
                1,
                20,
                1
        );
        when(processingService.listOwnedDocumentChunks(
                eq(currentUser),
                eq(201L),
                eq(301L),
                org.mockito.ArgumentMatchers.any(PageRequest.class)
        )).thenReturn(chunks);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, "test", List.of())
        );

        mockMvc(documentService, processingService).perform(MockMvcRequestBuilders.get(
                        "/api/v1/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunks", 201L, 301L
                ).param("page", "1").param("pageSize", "20")
                .header("X-Trace-Id", "af-test-document-chunks"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].chunkIndex"
                ).value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.data.items[0].content"
                ).value("Refund rules"));

        verify(processingService).listOwnedDocumentChunks(
                eq(currentUser),
                eq(201L),
                eq(301L),
                org.mockito.ArgumentMatchers.any(PageRequest.class)
        );
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }

    private static KnowledgeDocumentResponse response(
            String id,
            String knowledgeBaseId,
            String fileName,
            String fileType
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-14T12:00:00+08:00");
        return new KnowledgeDocumentResponse(
                id,
                knowledgeBaseId,
                fileName,
                fileType,
                7L,
                "PENDING",
                now,
                now
        );
    }

    private static KnowledgeChunkResponse chunkResponse(
            String id,
            String documentId,
            int chunkIndex,
            String content
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-14T12:00:00+08:00");
        return new KnowledgeChunkResponse(
                id,
                documentId,
                chunkIndex,
                content,
                "Refund",
                content.codePointCount(0, content.length()),
                2,
                now,
                now
        );
    }

    private static KnowledgeDocumentController controller(KnowledgeDocumentService service) {
        return new KnowledgeDocumentController(service, Mockito.mock(DocumentProcessingService.class));
    }

    private static MockMvc mockMvc(KnowledgeDocumentService service) {
        return mockMvc(service, Mockito.mock(DocumentProcessingService.class));
    }

    private static MockMvc mockMvc(
            KnowledgeDocumentService documentService,
            DocumentProcessingService processingService
    ) {
        return MockMvcBuilders
                .standaloneSetup(new KnowledgeDocumentController(documentService, processingService))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }
}
