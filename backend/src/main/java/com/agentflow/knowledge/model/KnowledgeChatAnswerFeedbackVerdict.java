package com.agentflow.knowledge.model;

/**
 * 中文：用户对一条不可变回答可提交的唯一二元判断。它不是模型质量分数、训练标签或检索控制信号。
 *
 * <p>English: The only binary judgement a user can submit for one immutable answer. It is not
 * a model-quality score, training label, or retrieval-control signal.</p>
 */
public enum KnowledgeChatAnswerFeedbackVerdict {
    HELPFUL,
    NOT_HELPFUL
}
