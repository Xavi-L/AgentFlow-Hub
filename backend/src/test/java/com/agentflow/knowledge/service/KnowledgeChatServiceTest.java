package com.agentflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.chat.ChatGateway;
import com.agentflow.knowledge.chat.ChatGatewayException;
import com.agentflow.knowledge.chat.ChatRequest;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatResponse;
import com.agentflow.knowledge.dto.KnowledgeContextResponse;
import com.agentflow.knowledge.dto.KnowledgeContextSourceResponse;
import com.agentflow.knowledge.dto.RetrieveContextTestRequest;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeChatServiceTest {

    @Mock
    private KnowledgeContextService knowledgeContextService;

    @Mock
    private ChatGateway chatGateway;

    private KnowledgeChatService knowledgeChatService;

    @BeforeEach
    void setUp() {
        knowledgeChatService = new KnowledgeChatService(knowledgeContextService, chatGateway);
    }

    @Test
    void shouldPassTheExactV8ContextToGatewayAndReturnV8TraceFieldsUnchanged() {
        ChatTestRequest request = new ChatTestRequest("  退款失败如何排查？  ", 3, 800, 256);
        KnowledgeContextResponse v8Response = v8Response();
        when(knowledgeContextService.retrieveContextTest(
                currentUser(),
                201L,
                new RetrieveContextTestRequest("  退款失败如何排查？  ", 3, 800)
        )).thenReturn(v8Response);
        when(chatGateway.generate(any(ChatRequest.class)))
                .thenReturn("先检查支付渠道错误码。[S2] 然后按规则处理。[S1] [S2]");

        KnowledgeChatResponse response = knowledgeChatService.chatTest(currentUser(), 201L, request);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatGateway).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().query()).isEqualTo(v8Response.query());
        assertThat(requestCaptor.getValue().context()).isEqualTo(v8Response.context());
        assertThat(requestCaptor.getValue().maxAnswerTokens()).isEqualTo(256);
        assertThat(response.answer()).isEqualTo("先检查支付渠道错误码。[S2] 然后按规则处理。[S1] [S2]");
        assertThat(response.query()).isEqualTo(v8Response.query());
        assertThat(response.topK()).isEqualTo(v8Response.topK());
        assertThat(response.maxContextTokens()).isEqualTo(v8Response.maxContextTokens());
        assertThat(response.usedContextTokens()).isEqualTo(v8Response.usedContextTokens());
        assertThat(response.skippedChunkCount()).isEqualTo(v8Response.skippedChunkCount());
        assertThat(response.maxAnswerTokens()).isEqualTo(256);
        assertThat(response.sources()).containsExactlyElementsOf(v8Response.sources());
        assertThat(response.citationIds()).containsExactly("S2", "S1");
        verify(knowledgeContextService).retrieveContextTest(
                currentUser(),
                201L,
                new RetrieveContextTestRequest("  退款失败如何排查？  ", 3, 800)
        );
    }

    @Test
    void shouldRejectEmptyV8ContextWithoutCallingTheGateway() {
        ChatTestRequest request = new ChatTestRequest("退款失败如何排查？", 3, 1, 256);
        when(knowledgeContextService.retrieveContextTest(
                currentUser(),
                201L,
                new RetrieveContextTestRequest("退款失败如何排查？", 3, 1)
        )).thenReturn(new KnowledgeContextResponse(
                "退款失败如何排查？",
                3,
                1,
                0,
                2,
                "",
                List.of()
        ));

        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, request),
                ErrorCode.KNOWLEDGE_CONTEXT_EMPTY
        );

        verifyNoInteractions(chatGateway);
    }

    @Test
    void shouldRejectAnUnknownCitationWithoutReturningAForgedSource() {
        stubV8Response();
        when(chatGateway.generate(any(ChatRequest.class))).thenReturn("请按不存在的来源处理。[S99]");

        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, validRequest()),
                ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID
        );

        verify(chatGateway).generate(any(ChatRequest.class));
    }

    @Test
    void shouldRejectMissingAndMalformedCitations() {
        stubV8Response();
        when(chatGateway.generate(any(ChatRequest.class))).thenReturn("没有给出来源标记。");

        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, validRequest()),
                ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID
        );

        when(chatGateway.generate(any(ChatRequest.class))).thenReturn("畸形引用。[S1, S2]");
        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, validRequest()),
                ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID
        );

        when(chatGateway.generate(any(ChatRequest.class))).thenReturn("合法引用 [S1] 旁边有伪造引用 [[S99]]。");
        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, validRequest()),
                ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID
        );

        when(chatGateway.generate(any(ChatRequest.class))).thenReturn("合法引用 [S1] 后有未闭合伪造引用 [S99");
        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, validRequest()),
                ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID
        );
    }

    @Test
    void shouldTranslateGatewayFailureWithoutLeakingProviderDetailsOrRetrying() {
        stubV8Response();
        when(chatGateway.generate(any(ChatRequest.class)))
                .thenThrow(new ChatGatewayException("provider body contains private diagnostics"));

        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, validRequest()),
                ErrorCode.KNOWLEDGE_CHAT_GATEWAY_UNAVAILABLE
        );

        verify(chatGateway).generate(any(ChatRequest.class));
    }

    @Test
    void shouldRejectAnInvalidAnswerBudgetBeforeCallingV8OrTheGateway() {
        ChatTestRequest invalidRequest = new ChatTestRequest("退款失败如何排查？", 3, 800, 4_097);

        assertBusinessCode(
                () -> knowledgeChatService.chatTest(currentUser(), 201L, invalidRequest),
                ErrorCode.COMMON_PARAM_INVALID
        );

        verifyNoInteractions(knowledgeContextService, chatGateway);
    }

    private void stubV8Response() {
        when(knowledgeContextService.retrieveContextTest(
                currentUser(),
                201L,
                new RetrieveContextTestRequest("退款失败如何排查？", 3, 800)
        )).thenReturn(v8Response());
    }

    private static ChatTestRequest validRequest() {
        return new ChatTestRequest("退款失败如何排查？", 3, 800, 256);
    }

    private static KnowledgeContextResponse v8Response() {
        return new KnowledgeContextResponse(
                "退款失败如何排查？",
                3,
                800,
                63,
                1,
                """
                        [S1]
                        Source: refund-rules.md
                        Title: 支付 / 退款
                        DocumentId: 301
                        ChunkId: 401
                        Content:
                        先检查支付渠道错误码。

                        [S2]
                        Source: payment-status.txt
                        Title:
                        DocumentId: 302
                        ChunkId: 402
                        Content:
                        核对支付状态。""",
                List.of(
                        new KnowledgeContextSourceResponse(
                                "S1",
                                "401",
                                "301",
                                "refund-rules.md",
                                "支付 / 退款",
                                0.93
                        ),
                        new KnowledgeContextSourceResponse(
                                "S2",
                                "402",
                                "302",
                                "payment-status.txt",
                                "",
                                0.88
                        )
                )
        );
    }

    private static void assertBusinessCode(Runnable invocation, ErrorCode expectedCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(expectedCode);
    }

    private static AuthenticatedUser currentUser() {
        return new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
    }
}
