package com.agentflow.agent.task.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;

/** Optional immutable version selection; execution values and lifecycle remain server-owned. */
@JsonDeserialize(using = CreateAgentTaskRequestDeserializer.class)
public record CreateAgentTaskRequest(
        @NotBlank(message = "userInput must not be blank") String userInput,
        Long configVersionId
) {
    public CreateAgentTaskRequest(String userInput) { this(userInput, null); }
}
