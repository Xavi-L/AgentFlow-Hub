package com.agentflow.agent.binding.dto;

import java.util.List;

/** Safe public projection of the Agent's ordered knowledge binding IDs. */
public record AgentKnowledgeBindingsResponse(List<String> knowledgeBaseIds) {
    public AgentKnowledgeBindingsResponse {
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
    }

    public static AgentKnowledgeBindingsResponse from(List<Long> ids) {
        return new AgentKnowledgeBindingsResponse(ids.stream().map(String::valueOf).toList());
    }
}
