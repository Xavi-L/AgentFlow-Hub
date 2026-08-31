package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V15ReportGenerateToolMigrationContractTest {

    @Test
    void shouldOnlyInsertTheThirdBuiltInToolWithoutChangingTablesOrDemoFixtures() throws Exception {
        String sql = migrationSql();

        assertThat(count(sql, "INSERT INTO tool_definition")).isEqualTo(1);
        assertThat(sql).doesNotContain(
                "CREATE TABLE",
                "ALTER TABLE",
                "DROP TABLE",
                "INSERT INTO mock_order",
                "INSERT INTO mock_payment_log",
                "'order_query'",
                "'payment_log_query'",
                "'ticket_query'",
                "'knowledge_search'"
        );
    }

    @Test
    void shouldSeedTheExactReportRuntimeConfiguration() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "290000000000000001",
                "'report_generate'",
                "'Report Generate'",
                "'BUILTIN'",
                "'ACTIVE'",
                "'LOW'",
                "10000",
                "\"handler\": \"reportGenerateTool\"",
                "\"readonly\": true",
                "\"required\": [\"title\", \"summary\"]",
                "\"additionalProperties\": false"
        );
    }

    @Test
    void shouldFreezeTheRestrictedStringAndSuggestionArrayBounds() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "\"title\"",
                "\"summary\"",
                "\"rootCause\"",
                "\"suggestions\"",
                "\"maxLength\": 255",
                "\"maxLength\": 4000",
                "\"minItems\": 1",
                "\"maxItems\": 20",
                "\"items\": {",
                "\"maxLength\": 1000"
        );
    }

    @Test
    void shouldDescribeMarkdownAsTheOnlyOutputField() throws Exception {
        String sql = migrationSql();
        String outputSchema = sql.substring(
                sql.indexOf("    '{\n      \"type\": \"object\",", sql.indexOf("}'::jsonb,"))
        );

        assertThat(outputSchema).contains(
                "\"markdown\": {\"type\": \"string\"}",
                "\"required\": [\"markdown\"]"
        ).doesNotContain(
                "\"title\"",
                "\"summary\"",
                "\"rootCause\"",
                "\"suggestions\""
        );
    }

    private static String migrationSql() throws IOException {
        try (InputStream input = V15ReportGenerateToolMigrationContractTest.class.getResourceAsStream(
                "/db/migration/V15__add_report_generate_tool.sql"
        )) {
            if (input == null) {
                throw new IOException("V15 migration resource is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
