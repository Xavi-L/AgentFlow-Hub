package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V14PaymentLogQueryToolMigrationContractTest {

    @Test
    void shouldOnlyInsertTheSecondBuiltInToolWithoutChangingExistingTablesOrFixtures() throws Exception {
        String sql = migrationSql();

        assertThat(count(sql, "INSERT INTO tool_definition")).isEqualTo(1);
        assertThat(sql).doesNotContain(
                "CREATE TABLE",
                "ALTER TABLE",
                "DROP TABLE",
                "INSERT INTO mock_order",
                "INSERT INTO mock_payment_log",
                "'report_generate'",
                "'ticket_query'",
                "'knowledge_search'"
        );
    }

    @Test
    void shouldSeedTheExactPaymentLogRuntimeConfigurationAndRestrictedAnyOfSchema() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "280000000000000001",
                "'payment_log_query'",
                "'Payment Log Query'",
                "'BUILTIN'",
                "'ACTIVE'",
                "'MEDIUM'",
                "5000",
                "\"handler\": \"paymentLogQueryTool\"",
                "\"readonly\": true",
                "\"anyOf\": [",
                "{\"required\": [\"orderNo\"]}",
                "{\"required\": [\"errorCode\"]}",
                "\"minimum\": 1",
                "\"maximum\": 20",
                "\"default\": 10",
                "\"additionalProperties\": false"
        );
    }

    @Test
    void shouldDescribeOnlyTheSafePaymentLogOutputFields() throws Exception {
        String sql = migrationSql();

        assertThat(sql).contains(
                "\"logs\"",
                "\"orderNo\"",
                "\"traceId\"",
                "\"level\"",
                "\"errorCode\"",
                "\"message\"",
                "\"occurredAt\""
        ).doesNotContain(
                "\"id\"",
                "\"createdAt\"",
                "\"userNo\""
        );
    }

    private static String migrationSql() throws IOException {
        try (InputStream input = V14PaymentLogQueryToolMigrationContractTest.class.getResourceAsStream(
                "/db/migration/V14__add_payment_log_query_tool.sql"
        )) {
            if (input == null) {
                throw new IOException("V14 migration resource is missing");
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
