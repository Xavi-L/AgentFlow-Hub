package com.agentflow.agent.binding.dto;

import java.util.List;

/** Safe public projection of the Agent's ordered enabled tool binding IDs. */
public record AgentToolBindingsResponse(List<String> toolIds) {
    public AgentToolBindingsResponse {
        toolIds = List.copyOf(toolIds);
    }

    public static AgentToolBindingsResponse from(List<Long> ids) {
        return new AgentToolBindingsResponse(ids.stream().map(String::valueOf).toList());
    }
}
