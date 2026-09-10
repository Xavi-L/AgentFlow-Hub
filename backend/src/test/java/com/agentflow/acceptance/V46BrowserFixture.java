package com.agentflow.acceptance;

import com.agentflow.AgentFlowApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Real public Agent/binding/task APIs and PostgreSQL; reuses controlled V43/V45 providers. */
public final class V46BrowserFixture {
    private V46BrowserFixture() { }

    public static void main(String[] args) {
        if (!System.getProperty("spring.datasource.url", "").matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agentflow_v43_browser")) {
            throw new IllegalArgumentException("V46 fixture requires the disposable loopback browser database");
        }
        SpringApplication.run(new Class<?>[] {AgentFlowApplication.class, ControlledProviders.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ControlledProviders extends V45BrowserFixture.ControlledProviders {
        @Bean @Override
        public ApplicationRunner seedBrowserFixture(JdbcTemplate jdbc, PasswordEncoder encoder) {
            return args -> {
                super.seedBrowserFixture(jdbc, encoder).run(args);
                // Historical dates keep the V43 regression Agent as the first active choice.
                for (int i = 0; i < 21; i++) {
                    jdbc.update("""
                            INSERT INTO agent_app(id,user_id,name,system_prompt,model_provider,model_name,
                              created_at,updated_at)
                            VALUES (?,?,?,'Explain recorded payment facts','openai-compatible','v43-controlled-model',
                              TIMESTAMPTZ '2026-01-01 00:00:00Z',TIMESTAMPTZ '2026-01-01 00:00:00Z')
                            """, 460000000000000001L + i, V43BrowserFixture.OWNER,
                            i == 0 ? "Retained invalid binding" : "Pagination Agent " + i);
                }
                jdbc.update("""
                        INSERT INTO knowledge_base(id,user_id,name,deleted_at)
                        VALUES (460000000000000100,?,'Deleted bound knowledge',CURRENT_TIMESTAMP)
                        """, V43BrowserFixture.OWNER);
                jdbc.update("""
                        INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id)
                        VALUES (460000000000000101,?,460000000000000001,460000000000000100)
                        """, V43BrowserFixture.OWNER);
                // V49 repair scenario: a legacy 21-binding configuration must remain readable in the UI.
                jdbc.update("""
                        INSERT INTO knowledge_base(id,user_id,name,created_at,updated_at)
                        VALUES (460000000000000102,?,'Legacy overflow knowledge',
                          TIMESTAMPTZ '2025-01-01 00:00:00Z',TIMESTAMPTZ '2025-01-01 00:00:00Z')
                        """, V43BrowserFixture.OWNER);
                for (int i = 0; i < 21; i++) {
                    long kbId = i == 0 ? 430000000000000005L
                            : (i == 20 ? 460000000000000102L : 450000000000000000L + i);
                    jdbc.update("""
                            INSERT INTO agent_knowledge_binding(id,user_id,agent_id,knowledge_base_id,priority)
                            VALUES (?,?,460000000000000002,?,?)
                            """, 490000000000001000L + i, V43BrowserFixture.OWNER, kbId, i);
                }
                Files.writeString(Path.of(System.getProperty("v43.control-dir")).resolve("agents-ready"), "V46");
            };
        }
    }
}
