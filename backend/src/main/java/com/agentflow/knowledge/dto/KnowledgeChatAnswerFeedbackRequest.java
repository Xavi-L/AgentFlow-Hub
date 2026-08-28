package com.agentflow.knowledge.dto;

import com.agentflow.knowledge.model.KnowledgeChatAnswerFeedbackVerdict;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;

/**
 * 中文：V12 的唯一客户端输入。answer、owner、知识库、事件 ID 与时间均只能由路径、鉴权上下文或
 * 服务端生成，不能由请求体提供。
 *
 * <p>English: V12's sole client input. The answer, owner, knowledge base, event ID, and time
 * come only from the path, authenticated context, or server; the request body cannot provide
 * them.</p>
 */
@JsonDeserialize(using = KnowledgeChatAnswerFeedbackRequestDeserializer.class)
public record KnowledgeChatAnswerFeedbackRequest(
        @NotNull(message = "verdict is required")
        KnowledgeChatAnswerFeedbackVerdict verdict
) {
}
