package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.ChatTestRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerResponse;
import com.agentflow.knowledge.dto.KnowledgeChatResponse;
import com.agentflow.knowledge.dto.KnowledgeContextSourceResponse;
import com.agentflow.knowledge.model.KnowledgeChatAnswer;
import com.agentflow.knowledge.repository.KnowledgeChatAnswerMapper;
import com.agentflow.user.security.AuthenticatedUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：V10 的持久化边界。它先调用已经完成 context 与 citation 校验的 V9，再把其结果冻结为
 * 一行 append-only 记录；按 ID 查询时只访问该行，绝不回到 V7/V8、Qdrant 或 ChatGateway。
 *
 * <p>English: V10's persistence boundary. It first calls V9 after context/citation
 * validation, then freezes the result as one append-only row. ID reads access only that row
 * and never return to V7/V8, Qdrant, or ChatGateway.</p>
 */
@Service
public class KnowledgeChatAnswerService {
    private static final TypeReference<List<KnowledgeContextSourceResponse>> SOURCES_TYPE =
            new TypeReference<List<KnowledgeContextSourceResponse>>() {
            };
    private static final TypeReference<List<String>> CITATION_IDS_TYPE =
            new TypeReference<List<String>>() {
            };

    private final KnowledgeChatService knowledgeChatService;
    private final KnowledgeChatAnswerMapper knowledgeChatAnswerMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeChatAnswerService(
            KnowledgeChatService knowledgeChatService,
            KnowledgeChatAnswerMapper knowledgeChatAnswerMapper,
            ObjectMapper objectMapper
    ) {
        this.knowledgeChatService = Objects.requireNonNull(
                knowledgeChatService,
                "knowledgeChatService must not be null"
        );
        this.knowledgeChatAnswerMapper = Objects.requireNonNull(
                knowledgeChatAnswerMapper,
                "knowledgeChatAnswerMapper must not be null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Generates exactly one V9 answer and stores it only after V9 has returned a valid,
     * citation-checked response. Any V9 failure occurs before this method reaches insert.
     */
    @Transactional
    public KnowledgeChatAnswerResponse chat(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            ChatTestRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        KnowledgeChatResponse generated = knowledgeChatService.chatTest(
                currentUser,
                knowledgeBaseId,
                request
        );
        KnowledgeChatAnswer auditRecord = freeze(
                currentUser,
                knowledgeBaseId,
                generated
        );

        int affectedRows = knowledgeChatAnswerMapper.insert(auditRecord);
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one inserted knowledge_chat_answer row");
        }

        return KnowledgeChatAnswerResponse.from(
                auditRecord,
                generated.sources(),
                generated.citationIds()
        );
    }

    /**
     * Reads the frozen audit row under one owner-and-knowledge-base SQL predicate. A missing
     * row is intentionally indistinguishable from another owner's row.
     */
    @Transactional(readOnly = true)
    public KnowledgeChatAnswerResponse getById(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            Long answerId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(answerId, "answerId must not be null");

        KnowledgeChatAnswer auditRecord = knowledgeChatAnswerMapper.selectOwnedById(
                answerId,
                knowledgeBaseId,
                currentUser.id()
        );
        if (auditRecord == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND);
        }

        return KnowledgeChatAnswerResponse.from(
                auditRecord,
                readSources(auditRecord.getSourcesSnapshotJson()),
                readCitationIds(auditRecord.getCitationIdsJson())
        );
    }

    private KnowledgeChatAnswer freeze(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            KnowledgeChatResponse generated
    ) {
        KnowledgeChatAnswer auditRecord = new KnowledgeChatAnswer();
        auditRecord.setUserId(currentUser.id());
        auditRecord.setKnowledgeBaseId(knowledgeBaseId);
        auditRecord.setQuery(generated.query());
        auditRecord.setAnswer(generated.answer());
        auditRecord.setTopK(generated.topK());
        auditRecord.setMaxContextTokens(generated.maxContextTokens());
        auditRecord.setUsedContextTokens(generated.usedContextTokens());
        auditRecord.setSkippedChunkCount(generated.skippedChunkCount());
        auditRecord.setMaxAnswerTokens(generated.maxAnswerTokens());
        auditRecord.setSourcesSnapshotJson(writeJson(generated.sources()));
        auditRecord.setCitationIdsJson(writeJson(generated.citationIds()));
        // PostgreSQL has the same default, but set the value before insert so the POST response
        // and the persisted audit record carry the same observable creation instant.
        auditRecord.setCreatedAt(OffsetDateTime.now());
        return auditRecord;
    }

    private String writeJson(Object snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Unable to serialize immutable chat-answer snapshot", serializationFailure);
        }
    }

    private List<KnowledgeContextSourceResponse> readSources(String serializedSources) {
        try {
            return List.copyOf(objectMapper.readValue(serializedSources, SOURCES_TYPE));
        } catch (JsonProcessingException deserializationFailure) {
            throw new IllegalStateException("Persisted chat-answer source snapshot is invalid", deserializationFailure);
        }
    }

    private List<String> readCitationIds(String serializedCitationIds) {
        try {
            return List.copyOf(objectMapper.readValue(serializedCitationIds, CITATION_IDS_TYPE));
        } catch (JsonProcessingException deserializationFailure) {
            throw new IllegalStateException("Persisted chat-answer citation snapshot is invalid", deserializationFailure);
        }
    }
}
