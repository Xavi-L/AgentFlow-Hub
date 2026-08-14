package com.agentflow.knowledge.chunk;

import java.util.List;

/**
 * 中文：轻量、可替换的 token 估算边界。V4 的 chunk 参数沿用既有 RAG 设计，单位是 estimated
 * tokens，而不是 Java 字符数。
 *
 * <p>English: A lightweight, replaceable token-estimation boundary. V4 keeps the
 * existing RAG contract: chunk settings are estimated tokens, not Java character
 * counts.
 */
public interface TokenEstimator {

    List<TokenSpan> tokenize(String text);

    default int estimate(String text) {
        return tokenize(text).size();
    }
}
