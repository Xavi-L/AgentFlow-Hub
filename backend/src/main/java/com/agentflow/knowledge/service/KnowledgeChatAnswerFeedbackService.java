package com.agentflow.knowledge.service;

import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackRequest;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackSummaryResponse;
import com.agentflow.knowledge.dto.KnowledgeChatAnswerFeedbackStatusResponse;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedback;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackSummary;
import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackStatus;
import com.agentflow.knowledge.repository.KnowledgeChatAnswerFeedbackMapper;
import com.agentflow.user.security.AuthenticatedUser;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中文：V12 的不可变二元反馈写入边界，以及 V13/V14/V15 的只读事件可见性。它只验证已持久化回答的
 * owner/知识库范围并写入或读取 feedback 行；不会重新检索、调用模型、修改回答快照，或把反馈解释为
 * 模型评测或训练数据。
 *
 * <p>English: V12's immutable binary-feedback write boundary and V13/V14/V15's read-only event
 * visibility. It only validates an existing persisted answer's owner/knowledge-base scope and
 * writes or reads a feedback row; it never retrieves again, calls a model, mutates an answer
 * snapshot, or treats feedback as model evaluation or training data.</p>
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
     * Returns only V15 raw submitted-event counts for one owner and knowledge base. The
     * aggregate's zero row is intentionally the same for empty, foreign-owner, and
     * wrong-knowledge-base scopes; this method neither pre-checks existence nor writes.
     */
    @Transactional(readOnly = true)
    public KnowledgeChatAnswerFeedbackSummaryResponse getSummaryOwnedByKnowledgeBase(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");

        KnowledgeChatAnswerFeedbackSummary summary = Objects.requireNonNull(
                knowledgeChatAnswerFeedbackMapper.selectSummaryOwnedByKnowledgeBase(
                        knowledgeBaseId,
                        currentUser.id()
                ),
                "feedback summary query must return one aggregate row"
        );
        return KnowledgeChatAnswerFeedbackSummaryResponse.from(summary);
    }

    /**
     * Reads only the V13 visibility state of one scoped answer. A parent answer with no feedback
     * remains a successful read; a missing, foreign, or wrong-knowledge-base parent stays hidden
     * behind the existing answer-not-found contract.
     */
    @Transactional(readOnly = true)
    public KnowledgeChatAnswerFeedbackStatusResponse getFeedbackStatus(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            Long answerId
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(answerId, "answerId must not be null");

        KnowledgeChatAnswerFeedbackStatus status = knowledgeChatAnswerFeedbackMapper
                .selectStatusOwnedByAnswerId(answerId, knowledgeBaseId, currentUser.id());
        if (status == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CHAT_ANSWER_NOT_FOUND);
        }
        if (!status.hasSubmittedFeedback()) {
            return KnowledgeChatAnswerFeedbackStatusResponse.unsubmitted();
        }

        KnowledgeChatAnswerFeedback feedback = new KnowledgeChatAnswerFeedback();
        feedback.setId(status.getFeedbackId());
        feedback.setAnswerId(status.getAnswerId());
        feedback.setVerdict(status.getVerdict());
        feedback.setCreatedAt(status.getCreatedAt());
        return KnowledgeChatAnswerFeedbackStatusResponse.submitted(
                KnowledgeChatAnswerFeedbackResponse.from(feedback)
        );
    }

    /**
     * Returns only submitted V12 events for one owner and knowledge base. A zero-row parent
     * JOIN result intentionally remains an empty page, whether that is because the knowledge
     * base is empty, foreign, or outside the current owner's scope.
     */
    @Transactional(readOnly = true)
    public PageResult<KnowledgeChatAnswerFeedbackResponse> listOwnedByKnowledgeBase(
            AuthenticatedUser currentUser,
            Long knowledgeBaseId,
            PageRequest pageRequest
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");

        Page<KnowledgeChatAnswerFeedback> databasePage = new Page<>(
                pageRequest.getPage(),
                pageRequest.getPageSize()
        );
        knowledgeChatAnswerFeedbackMapper.selectPageOwnedByKnowledgeBase(
                databasePage,
                knowledgeBaseId,
                currentUser.id()
        );

        return PageResult.of(
                databasePage.getRecords().stream()
                        .map(KnowledgeChatAnswerFeedbackResponse::from)
                        .toList(),
                pageRequest.getPage(),
                pageRequest.getPageSize(),
                databasePage.getTotal()
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
