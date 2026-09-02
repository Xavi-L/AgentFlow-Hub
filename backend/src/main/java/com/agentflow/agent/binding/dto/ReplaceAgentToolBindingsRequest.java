package com.agentflow.agent.binding.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Strict full-replacement request; enabled, priority, and tool policy are server-owned. */
@JsonDeserialize(using = ReplaceAgentToolBindingsRequestDeserializer.class)
public record ReplaceAgentToolBindingsRequest(
        @NotNull
        @Size(max = 20, message = "toolIds must not contain more than 20 items")
        List<@Positive Long> toolIds
) {
    public ReplaceAgentToolBindingsRequest {
        toolIds = toolIds == null ? null : List.copyOf(toolIds);
    }
}
