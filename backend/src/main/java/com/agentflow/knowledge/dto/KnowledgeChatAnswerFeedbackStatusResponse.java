package com.agentflow.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * 中文：V13 单条不可变回答的只读反馈状态。尚未反馈时 JSON 只包含 submitted=false；
 * 已反馈时嵌套的 feedback 是原始 V12 不可变事件，而非新建的状态记录。
 *
 * <p>English: Read-only V13 feedback status for one immutable answer. Before feedback, JSON
 * contains only submitted=false; after feedback, nested feedback is the original immutable V12
 * event rather than a newly created status record.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeChatAnswerFeedbackStatusResponse(
        boolean submitted,
        KnowledgeChatAnswerFeedbackResponse feedback
) {
    public KnowledgeChatAnswerFeedbackStatusResponse {
        if (submitted != (feedback != null)) {
            throw new IllegalArgumentException(
                    "feedback must be present if and only if submitted is true"
            );
        }
    }

    public static KnowledgeChatAnswerFeedbackStatusResponse unsubmitted() {
        return new KnowledgeChatAnswerFeedbackStatusResponse(false, null);
    }

    public static KnowledgeChatAnswerFeedbackStatusResponse submitted(
            KnowledgeChatAnswerFeedbackResponse feedback
    ) {
        return new KnowledgeChatAnswerFeedbackStatusResponse(
                true,
                Objects.requireNonNull(feedback, "feedback must not be null")
        );
    }
}
