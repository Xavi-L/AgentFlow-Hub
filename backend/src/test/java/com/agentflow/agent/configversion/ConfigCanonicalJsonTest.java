package com.agentflow.agent.configversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigCanonicalJsonTest {
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void sharesExactUtf8GoldenVectorsWithCli() throws Exception {
        Path fixture = Path.of("../scripts/fixtures/config-canonical-json-v1-vectors.json");
        if (!Files.exists(fixture)) fixture = Path.of("scripts/fixtures/config-canonical-json-v1-vectors.json");
        var vectors = json.readTree(Files.readString(fixture));
        assertThat(vectors.path("algorithmVersion").asText()).isEqualTo(ConfigCanonicalJson.ALGORITHM_VERSION);
        assertThat(vectors.path("vectors")).hasSize(8);
        for (var vector : vectors.path("vectors")) {
            var input = json.readTree(vector.path("inputJson").asText());
            assertThat(ConfigCanonicalJson.canonicalJson(input)).as(vector.path("name").asText())
                    .isEqualTo(vector.path("canonical").asText());
            assertThat(ConfigCanonicalJson.sha256(input)).isEqualTo(vector.path("sha256").asText());
        }
    }

    @Test
    void rejectsNonFiniteAndInvalidUnicodeInsteadOfGeneratingAmbiguousBytes() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> ConfigCanonicalJson.canonicalJson(DoubleNode.valueOf(value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ConfigCanonicalJson.canonicalJson(json.getNodeFactory().textNode("\uD800")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalizesSchemaNumbersWithoutChangingTheLegacyToolFingerprint() throws Exception {
        var first = json.readTree("{\"tools\":[{\"inputSchema\":{\"minimum\":1},\"inputSchemaHash\":\"legacy-a\"}]}");
        var second = json.readTree("{\"tools\":[{\"inputSchema\":{\"minimum\":1.0},\"inputSchemaHash\":\"legacy-b\"}]}");
        assertThat(ConfigCanonicalJson.effectiveConfigHash(first)).isEqualTo(ConfigCanonicalJson.effectiveConfigHash(second));
        assertThat(first.path("tools").get(0).path("inputSchemaHash").asText()).isEqualTo("legacy-a");
    }

    @Test
    void excludesSourceLabelsButRetainsValuesPolicyAndActualPromptInputs() throws Exception {
        ObjectNode snapshot = (ObjectNode) json.readTree("""
                {"snapshotVersion":"agent-task-snapshot-v2",
                 "agent":{"agentId":"1","status":"ACTIVE","systemPrompt":" 原始\\n"},
                 "runtime":{"applicationRevision":"development","promptRulesVersion":"v1"},
                 "tools":[{"toolId":"2","name":"Order","description":"Lookup"}],
                 "executionSettings":{"policyVersion":"v1","decisionMaxOutputTokens":512,
                                      "sources":{"decisionMaxOutputTokens":"DEPLOYMENT_DEFAULT"}}}
                """);
        String before = ConfigCanonicalJson.effectiveConfigHash(snapshot);
        ObjectNode same = snapshot.deepCopy();
        ((ObjectNode) same.path("agent")).put("agentId", "different").put("status", "DISABLED");
        ((ObjectNode) same.path("executionSettings").path("sources"))
                .put("decisionMaxOutputTokens", "AGENT_OVERRIDE");
        assertThat(ConfigCanonicalJson.effectiveConfigHash(same)).isEqualTo(before);
        assertThat(snapshot.path("agent").path("agentId").asText()).isEqualTo("1");
        ((ObjectNode) same.path("executionSettings")).put("decisionMaxOutputTokens", 513);
        assertThat(ConfigCanonicalJson.effectiveConfigHash(same)).isNotEqualTo(before);
        ObjectNode nameChange = snapshot.deepCopy();
        ((ObjectNode) nameChange.path("tools").get(0)).put("name", "Different model input");
        assertThat(ConfigCanonicalJson.effectiveConfigHash(nameChange)).isNotEqualTo(before);
        ObjectNode promptChange = snapshot.deepCopy();
        ((ObjectNode) promptChange.path("agent")).put("systemPrompt", "原始\n");
        assertThat(ConfigCanonicalJson.effectiveConfigHash(promptChange)).isNotEqualTo(before);
    }
}
