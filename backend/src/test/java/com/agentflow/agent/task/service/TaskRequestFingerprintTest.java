package com.agentflow.agent.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TaskRequestFingerprintTest {

    private final TaskRequestFingerprint fingerprint = new TaskRequestFingerprint(new ObjectMapper());

    @Test
    void shouldMatchTheFrozenCanonicalJsonVector() {
        TaskRequestFingerprint.Fingerprint result = fingerprint.calculate(1001L, "原始 userInput");

        assertThat(result.canonicalJson()).isEqualTo(
                "{\"agentId\":\"1001\",\"input\":\"原始 userInput\","
                        + "\"version\":\"agent-task-request-v1\"}"
        );
        assertThat(result.sha256()).isEqualTo(
                "82999332649b1d6137912dffb59ea0b7e3afdd5274fdd3a2d6af7086de2a0364"
        );
    }

    @Test
    void shouldKeepOriginalWhitespaceInTheFingerprint() {
        assertThat(fingerprint.calculate(1001L, " input ").sha256())
                .isNotEqualTo(fingerprint.calculate(1001L, "input").sha256());
    }
}
