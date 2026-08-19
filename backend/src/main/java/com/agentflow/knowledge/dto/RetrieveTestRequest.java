package com.agentflow.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** HTTP input for the deliberately small V7 semantic-retrieval verification endpoint. */
public record RetrieveTestRequest(
        @NotBlank(message = "query must not be blank")
        @Size(max = 1000, message = "query must not exceed 1000 characters")
        String query,
        @Min(value = 1, message = "topK must be between 1 and 10")
        @Max(value = 10, message = "topK must be between 1 and 10")
        Integer topK
) {
}
