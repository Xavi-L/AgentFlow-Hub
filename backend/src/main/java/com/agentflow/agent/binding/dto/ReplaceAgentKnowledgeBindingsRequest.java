package com.agentflow.agent.binding.dto;

import static com.agentflow.agent.AgentKnowledgeLimits.MAX_KNOWLEDGE_BINDINGS;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Strict full-replacement request; owner and binding metadata remain server-owned. */
@JsonDeserialize(using = ReplaceAgentKnowledgeBindingsRequestDeserializer.class)
public record ReplaceAgentKnowledgeBindingsRequest(
        @NotNull
        @Size(max = MAX_KNOWLEDGE_BINDINGS,
                message = "knowledgeBaseIds must not contain more than " + MAX_KNOWLEDGE_BINDINGS + " items")
        List<@Positive Long> knowledgeBaseIds
) {
    public ReplaceAgentKnowledgeBindingsRequest {
        knowledgeBaseIds = knowledgeBaseIds == null ? null : List.copyOf(knowledgeBaseIds);
    }
}
