package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.chat.ChatGateway;
import com.agentflow.knowledge.chat.ChatGatewayException;
import com.agentflow.knowledge.chat.ChatRequest;
import com.agentflow.knowledge.chat.CitationReferenceExtractor;
import com.agentflow.knowledge.chat.CitationValidationException;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatResponse;
import com.agentflow.knowledge.dto.KnowledgeContextResponse;
import com.agentflow.knowledge.dto.RetrieveContextTestRequest;
import com.agentflow.user.security.AuthenticatedUser;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 中文：V9 的单次、可追溯 RAG 回答编排。它只消费 V8 的 KnowledgeContextResponse，绝不回退到
 * V7、embedding、Qdrant 或 context 重装配。
 *
 * <p>English: V9 orchestration for one traceable RAG answer. It consumes only V8's
 * KnowledgeContextResponse and never falls back to V7, embedding, Qdrant, or context reassembly.
 */
@Service
public class KnowledgeChatService {
    static final int MAX_ANSWER_TOKENS = 4_096;

    private final KnowledgeContextService knowledgeContextService;
    private final ChatGateway chatGateway;
    private final CitationReferenceExtractor citationReferenceExtractor = new CitationReferenceExtractor();

    public KnowledgeChatService(
            KnowledgeContextService knowledgeContextService,
            ChatGateway chatGateway
    ) {
        this.knowledgeContextService = Objects.requireNonNull(
                knowledgeContextService,
                "knowledgeContextService must not be null"
        );
        this.chatGateway = Objects.requireNonNull(chatGateway, "chatGateway must not be null");
    }

    public KnowledgeChatResponse chatTest(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            ChatTestRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(request, "request must not be null");
        int maxAnswerTokens = normalizeMaxAnswerTokens(request.maxAnswerTokens());

        // V9 delegates all scope checks, retrieval, budgeting, [S#] assignment, and context
        // construction to V8. It must never recreate any of those steps from lower layers.
        KnowledgeContextResponse contextResponse = knowledgeContextService.retrieveContextTest(
                currentUser,
                knowledgeBaseId,
                new RetrieveContextTestRequest(request.query(), request.topK(), request.maxContextTokens())
        );
        if (contextResponse.sources().isEmpty() || contextResponse.context().isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CONTEXT_EMPTY);
        }

        String answer;
        try {
            answer = chatGateway.generate(new ChatRequest(
                    contextResponse.query(),
                    contextResponse.context(),
                    maxAnswerTokens
            ));
        } catch (ChatGatewayException gatewayFailure) {
            // Keep provider URL, body, and configuration diagnostics in the server-side cause.
            throw new BusinessException(ErrorCode.KNOWLEDGE_CHAT_GATEWAY_UNAVAILABLE);
        }

        List<String> citationIds = validateCitations(answer, contextResponse);
        return new KnowledgeChatResponse(
                answer,
                contextResponse.query(),
                contextResponse.topK(),
                contextResponse.maxContextTokens(),
                contextResponse.usedContextTokens(),
                contextResponse.skippedChunkCount(),
                maxAnswerTokens,
                contextResponse.sources(),
                citationIds
        );
    }

    private List<String> validateCitations(String answer, KnowledgeContextResponse contextResponse) {
        Set<String> allowedCitationIds = contextResponse.sources().stream()
                .map(source -> source.citationId())
                .collect(Collectors.toUnmodifiableSet());
        try {
            return citationReferenceExtractor.extractAndValidate(answer, allowedCitationIds);
        } catch (CitationValidationException citationFailure) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CHAT_CITATION_INVALID);
        }
    }

    private static int normalizeMaxAnswerTokens(Integer maxAnswerTokens) {
        if (maxAnswerTokens == null || maxAnswerTokens < 1 || maxAnswerTokens > MAX_ANSWER_TOKENS) {
            throw new BusinessException(
                    ErrorCode.COMMON_PARAM_INVALID,
                    "maxAnswerTokens must be between 1 and " + MAX_ANSWER_TOKENS
            );
        }
        return maxAnswerTokens;
    }
}
