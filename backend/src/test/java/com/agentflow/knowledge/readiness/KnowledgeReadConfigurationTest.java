package com.agentflow.knowledge.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentflow.knowledge.dto.KnowledgeBaseResponse;
import com.agentflow.knowledge.model.KnowledgeBase;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KnowledgeReadConfigurationTest {
    @Test
    void shouldResolveTheCanonicalProfileAndStrategy() {
        assertThat(KnowledgeReadConfiguration.embeddingProfileCode("dashscope", "text-embedding-v4"))
                .isEqualTo("dashscope-te-v4-1024-cosine");
        assertThat(KnowledgeReadConfiguration.chunkStrategyVersion(800, 120))
                .isEqualTo("structured-token-v1");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "openai-compatible,text-embedding-v3",
            "dashscope,text-embedding-v3",
            "openai-compatible,text-embedding-v4",
            "DASHSCOPE,text-embedding-v4",
            "NULL,text-embedding-v4",
            "dashscope,NULL"
    }, nullValues = "NULL")
    void shouldNotInventAProfileForUnsupportedOrMissingConfiguration(String provider, String model) {
        assertThat(KnowledgeReadConfiguration.embeddingProfileCode(provider, model)).isNull();
    }

    @ParameterizedTest
    @CsvSource(value = {"799,120", "800,119", "NULL,120", "800,NULL"}, nullValues = "NULL")
    void shouldNotInventAStrategyForNoncanonicalChunkSettings(Integer size, Integer overlap) {
        assertThat(KnowledgeReadConfiguration.chunkStrategyVersion(size, overlap)).isNull();
    }

    @Test
    void shouldExposeIndependentConfigurationFactsAndExplicitNulls() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(201L);
        knowledgeBase.setEmbeddingProvider("dashscope");
        knowledgeBase.setEmbeddingModel("text-embedding-v4");
        knowledgeBase.setChunkSize(400);
        knowledgeBase.setChunkOverlap(120);
        knowledgeBase.setStatus("DISABLED");
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

        JsonNode response = mapper.valueToTree(KnowledgeBaseResponse.from(knowledgeBase));

        assertThat(response.path("embeddingProfileCode").asText())
                .isEqualTo("dashscope-te-v4-1024-cosine");
        assertThat(response.has("chunkStrategyVersion")).isTrue();
        assertThat(response.path("chunkStrategyVersion").isNull()).isTrue();
        assertThat(response.path("chunkSize").asInt()).isEqualTo(400);
        assertThat(response.path("status").asText()).isEqualTo("DISABLED");

        knowledgeBase.setEmbeddingModel("text-embedding-v3");
        knowledgeBase.setChunkSize(800);
        JsonNode legacyResponse = mapper.valueToTree(KnowledgeBaseResponse.from(knowledgeBase));
        assertThat(legacyResponse.has("embeddingProfileCode")).isTrue();
        assertThat(legacyResponse.path("embeddingProfileCode").isNull()).isTrue();
        assertThat(legacyResponse.path("chunkStrategyVersion").asText()).isEqualTo("structured-token-v1");
        assertThat(legacyResponse.path("embeddingModel").asText()).isEqualTo("text-embedding-v3");
    }
}
