package com.agentflow.agent.task.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;

/** Task creation accepts only user input; identity and execution configuration are server-owned. */
@JsonDeserialize(using = CreateAgentTaskRequestDeserializer.class)
public record CreateAgentTaskRequest(
        @NotBlank(message = "userInput must not be blank") String userInput
) {
}
