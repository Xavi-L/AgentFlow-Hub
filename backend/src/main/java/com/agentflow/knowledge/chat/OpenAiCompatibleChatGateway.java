package com.agentflow.knowledge.chat;

import com.agentflow.config.OpenAiChatProperties;
import com.agentflow.infra.llm.LlmChatRequest;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmGatewayException;
import com.agentflow.infra.llm.LlmMessage;
import com.agentflow.infra.llm.LlmMessageRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * V9 domain adapter over the generic LLM boundary. It owns the fixed knowledge instruction,
 * while V9 service retains citation validation and source provenance.
 */
public final class OpenAiCompatibleChatGateway implements ChatGateway {
    static final String SYSTEM_INSTRUCTION = """
            Answer only from the supplied knowledge-base context.
            Every answer must include at least one citation marker exactly in the form [S#].
            Use only citation markers already present in the supplied context, and never invent a source.
            """;
    private static final BigDecimal TEMPERATURE = new BigDecimal("0.2");
    private static final BigDecimal TOP_P = new BigDecimal("0.8");

    private final OpenAiChatProperties properties;
    private final LlmGateway llmGateway;

    OpenAiCompatibleChatGateway(OpenAiChatProperties properties, LlmGateway llmGateway) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.llmGateway = Objects.requireNonNull(llmGateway, "llmGateway must not be null");
    }

    @Override
    public String generate(ChatRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return llmGateway.chat(new LlmChatRequest(
                    "openai-compatible",
                    properties.getChatModel(),
                    List.of(
                            new LlmMessage(LlmMessageRole.SYSTEM, SYSTEM_INSTRUCTION),
                            new LlmMessage(
                                    LlmMessageRole.USER,
                                    "Question:\n" + request.query() + "\n\nContext:\n" + request.context()
                            )
                    ),
                    TEMPERATURE,
                    TOP_P,
                    request.maxAnswerTokens()
            )).content();
        } catch (LlmGatewayException gatewayFailure) {
            throw new ChatGatewayException("OpenAI-compatible chat request failed");
        }
    }
}
