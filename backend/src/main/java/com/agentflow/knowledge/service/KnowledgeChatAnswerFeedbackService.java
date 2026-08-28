package com.agentflow.knowledge.service;

import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackResponse;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.repository.KnowledgeChatAnswerFeedbackMapper;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：V12 的不可变二元反馈写入边界。它只验证已持久化回答的 owner/知识库范围并写入或读取反馈
 * 行；不会重新检索、调用模型、修改回答快照，或把反馈解释为模型评测或训练数据。
 *
 * <p>English: V12's immutable binary-feedback write boundary. It only validates an existing
 * persisted answer's owner/knowledge-base scope and writes or reads a feedback row; it never
 * retrieves again, calls a model, mutates an answer snapshot, or treats feedback as model
 * evaluation or training data.</p>
 */
@Service
public class KnowledgeChatAnswerFeedbackService {
    private final KnowledgeChatAnswerFeedbackMapper knowledgeChatAnswerFeedbackMapper;

    public KnowledgeChatAnswerFeedbackService(
            KnowledgeChatAnswerFeedbackMapper knowledgeChatAnswerFeedbackMapper
    ) {
        this.knowledgeChatAnswerFeedbackMapper = Objects.requireNonNull(
                knowledgeChatAnswerFeedbackMapper,
                "knowledgeChatAnswerFeedbackMapper must not be null"
        );
    }

    /**
     * Creates one feedback event when absent. A same-verdict retry returns the frozen event;
     * an opposite verdict returns 409 and neither path can update the original event.
     */
    @Transactional
    public KnowledgeChatAnswerFeedbackResponse submitFeedback(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            Long answerId,
            KnowledgeChatAnswerFeedbackRequest request
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(answerId, "answerId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(request.verdict(), "request verdict must not be null");

        // The answer owner/knowledge-base predicate lives in the same JOIN as this feedback
        // lookup, so an absent, foreign, or wrong-knowledge-base answer cannot disclose any
        // feedback event before the atomic scoped insert/re-read below.
        KnowledgeChatAnswerFeedback existing = knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(
                answerId,
                knowledgeBaseId,
                currentUser.id()
        );
        if (existing != null) {
            return resolveExisting(existing, request);
        }

        KnowledgeChatAnswerFeedback created = new KnowledgeChatAnswerFeedback();
        created.setId(IdWorker.getId());
        created.setAnswerId(answerId);
        created.setVerdict(request.verdict().name());
        created.setCreatedAt(OffsetDateTime.now());

        int affectedRows = knowledgeChatAnswerFeedbackMapper.insertIfAbsent(
                created,
                knowledgeBaseId,
                currentUser.id()
        );
        if (affectedRows == 1) {
            return KnowledgeChatAnswerFeedbackResponse.from(created);
        }
        if (affectedRows != 0) {
            throw new IllegalStateException(
                    "Expected zero or one inserted knowledge_chat_answer_feedback row"
            );
        }

        // PostgreSQL ON CONFLICT DO NOTHING has made this a safe concurrent retry path rather
        // than a failed unique-key statement that would abort the transaction.
        existing = knowledgeChatAnswerFeedbackMapper.selectOwnedByAnswerId(
                answerId,
                knowledgeBaseId,
                currentUser.id()
        );
        if (existing == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND);
        }
        return resolveExisting(existing, request);
    }

    private KnowledgeChatAnswerFeedbackResponse resolveExisting(
            KnowledgeChatAnswerFeedback existing,
            KnowledgeChatAnswerFeedbackRequest request
    ) {
        if (!request.verdict().name().equals(existing.getVerdict())) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CHAT_ANSWER_FEEDBACK_CONFLICT);
        }
        return KnowledgeChatAnswerFeedbackResponse.from(existing);
    }
}
