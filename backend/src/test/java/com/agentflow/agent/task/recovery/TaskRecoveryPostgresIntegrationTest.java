package com.agentflow.agent.task.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentflow.agent.task.dto.AgentTaskResponseMapper;
import com.agentflow.agent.task.dto.SafeTaskEventProjector;
import com.agentflow.agent.task.dto.SafeTaskPayloadProjector;
import com.agentflow.agent.task.model.AgentTaskEvent;
import com.agentflow.agent.task.repository.AgentTaskEventMapper;
import com.agentflow.agent.task.service.TaskEventAppender;
import com.agentflow.agent.trace.repository.LlmCallLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Transaction-level fixtures only; real HTTP entry and killed JVM evidence live in the process suite. */
@SpringJUnitConfig(TaskRecoveryPostgresIntegrationTest.DatabaseConfiguration.class)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class TaskRecoveryPostgresIntegrationTest {
    private static final String RUN = "0b71e626-4919-4468-a10f-9b74cbff1df1";
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TaskRecoveryTransactionService recovery;
    @Autowired TaskRecoveryMapper mapper;
    @Autowired AgentTaskEventMapper events;
    @Autowired ObjectMapper json;

    @BeforeEach
    void fixture() {
        jdbc.execute("DROP FUNCTION IF EXISTS recovery_test_fault() CASCADE");
        jdbc.execute("TRUNCATE app_user CASCADE");
        jdbc.update("INSERT INTO app_user (id, username, email, password_hash, display_name) VALUES (1101, 'recovery', 'recovery@example.test', 'hash', 'Recovery fixture')");
        jdbc.update("""
                INSERT INTO agent_app (id, user_id, name, system_prompt, model_provider, model_name,
                  max_steps, max_tool_calls, max_tokens, timeout_seconds, status)
                VALUES (2101,1101,'Recovery fixture','Frozen prompt','openai-compatible','historical-model',6,4,8000,120,'ACTIVE')
                """);
    }

    @Test
    void appliesNullableV22AndSettlesQueuedAndExpiredRunningWithoutManufacturedUsage() throws Exception {
        task(101, false, "v1");
        task(102, true, "v2");
        assertThat(mapper.selectCandidateIds(0, 1)).containsExactly(101L);
        assertThat(mapper.selectCandidateIds(101, 1)).containsExactly(102L);
        assertThat(jdbc.queryForObject("SELECT recovery_metadata IS NULL FROM agent_task WHERE id=101", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='22' AND success", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_task SET recovery_metadata='[]'::jsonb WHERE id=101"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(recovery.recover(101, RUN)).isTrue();
        assertThat(recovery.recover(102, RUN)).isTrue();
        JsonNode queued = taskJson(101);
        JsonNode running = taskJson(102);
        assertThat(queued.path("status").asText()).isEqualTo("FAILED");
        assertThat(queued.path("started_at").isNull()).isTrue();
        assertThat(queued.path("error_code").asText()).isEqualTo("TASK_RESTART_DISPATCH_LOST");
        assertThat(queued.at("/recovery_metadata/executionOutcome").asText()).isEqualTo("NOT_STARTED");
        assertThat(queued.at("/recovery_metadata/recordCompleteness").asText()).isEqualTo("COMPLETE");
        assertThat(queued.at("/recovery_metadata/recordedUsage/tokenUsageQuality").asText()).isEqualTo("UNKNOWN");
        assertThat(running.path("status").asText()).isEqualTo("FAILED");
        assertThat(running.path("error_code").asText()).isEqualTo("TASK_RESTART_INTERRUPTED");
        assertThat(running.at("/recovery_metadata/recordCompleteness").asText()).isEqualTo("UNCONFIRMED");
        assertThat(running.at("/recovery_metadata/executionOutcome").asText()).isEqualTo("UNKNOWN");
        assertThat(running.path("token_usage_quality").asText()).isEqualTo("UNKNOWN");
        assertThat(running.path("total_tokens").asInt()).isZero();
        assertThat(eventTypes(101)).containsExactly("TASK_CREATED", "TASK_FAILED");
        assertThat(eventTypes(102)).containsExactly("TASK_CREATED", "TASK_STARTED", "TASK_FAILED");
        assertThat(mapper.hasCandidates()).isFalse();
    }

    @Test
    void preservesSuccessfulFactsMixedUsageCountersAndHistoricalSnapshotsAndProjectsRecovery() throws Exception {
        task(101, true, "v1");
        step(201, 101, 0, "LLM_DECISION", false);
        step(202, 101, 1, "LLM_FINAL_GENERATION", true);
        step(203, 101, 2, "TOOL_CALL", true);
        llm(301, 101, 201, "DECISION", 11, 7, "EXACT");
        llm(302, 101, 202, "FINAL_GENERATION", 13, 5, "ESTIMATED");
        tool(401, 101, 203, false);
        tool(402, 101, 203, true);
        jdbc.update("UPDATE agent_task SET decision_turns_used=3,tool_calls_used=2 WHERE id=101");
        String successfulStep = rowJson("agent_step", 201);
        String successfulTool = rowJson("tool_call_log", 401);
        String finalCall = rowJson("llm_call_log", 302);
        JsonNode snapshot = taskJson(101).path("execution_snapshot");

        recovery.recover(101, RUN);
        JsonNode task = taskJson(101);
        assertThat(task.path("input_tokens").asInt()).isEqualTo(24);
        assertThat(task.path("output_tokens").asInt()).isEqualTo(12);
        assertThat(task.path("total_tokens").asInt()).isEqualTo(36);
        assertThat(task.path("decision_turns_used").asInt()).isEqualTo(3);
        assertThat(task.path("tool_calls_used").asInt()).isEqualTo(2);
        assertThat(task.path("token_usage_quality").asText()).isEqualTo("UNKNOWN");
        assertThat(task.at("/recovery_metadata/recordedUsage/tokenUsageQuality").asText()).isEqualTo("MIXED");
        assertThat(task.at("/recovery_metadata/recordedLlmCalls").asInt()).isEqualTo(2);
        assertThat(task.at("/recovery_metadata/recordedToolCalls").asInt()).isEqualTo(2);
        assertThat(task.path("execution_snapshot")).isEqualTo(snapshot);
        assertThat(task.path("final_answer").isNull()).isTrue();
        assertThat(task.path("citations")).isEmpty();
        assertThat(rowJson("agent_step", 201)).isEqualTo(successfulStep);
        assertThat(rowJson("tool_call_log", 401)).isEqualTo(successfulTool);
        assertThat(rowJson("llm_call_log", 302)).isEqualTo(finalCall);
        assertThat(json.readTree(rowJson("tool_call_log", 402)).path("error_code").asText()).isEqualTo("TASK_RESTART_INTERRUPTED");
        assertThat(json.readTree(rowJson("tool_call_log", 402)).path("latency_ms").isNull()).isTrue();
        assertThat(json.readTree(rowJson("agent_step", 202)).path("latency_ms").isNull()).isTrue();
        assertThat(eventTypes(101)).doesNotContain("ANSWER_CHUNK", "TASK_COMPLETED");

        var payloads = new SafeTaskPayloadProjector(json);
        var dto = new AgentTaskResponseMapper(payloads).toResponse(mapper.lockTask(101));
        assertThat(dto.recovery()).isEqualTo(task.path("recovery_metadata"));
        AgentTaskEvent terminal = events.selectByTaskIdAfterSequence(101, 2).getFirst();
        JsonNode summary = new SafeTaskEventProjector(payloads, json).toResponse(terminal).payload().path("recovery");
        assertThat(summary.size()).isEqualTo(6);
        assertThat(summary.path("recoveryRunId").asText()).isEqualTo(RUN);
        assertThat(summary.has("recordedUsage")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"step", "tool", "task", "sequence", "event", "zero-row"})
    void rollsBackEveryWriteAndOriginalSequenceWhenDatabaseRejectsSettlement(String stage) throws Exception {
        task(101, true, "v2");
        step(201, 101, 0, "TOOL_CALL", true);
        tool(301, 101, 201, true);
        List<String> before = state(101);
        installFault(stage);
        assertThatThrownBy(() -> recovery.recover(101, RUN)).isInstanceOf(RuntimeException.class);
        assertThat(state(101)).isEqualTo(before);
        assertThat(mapper.hasCandidates()).isTrue();
        jdbc.execute("DROP FUNCTION recovery_test_fault() CASCADE");
        assertThat(recovery.recover(101, RUN)).isTrue();
        assertThat(recovery.recover(101, UUID.randomUUID().toString())).isFalse();
        assertThat(eventTypes(101)).containsExactly("TASK_CREATED", "TASK_STARTED", "TASK_FAILED");
    }

    @Test
    void rejectsQueuedExecutionEvidenceAndContradictoryNonzeroTaskSummaryWithoutWrites() throws Exception {
        task(101, false, "v1");
        step(201, 101, 0, "LLM_DECISION", true);
        List<String> queued = state(101);
        assertThatThrownBy(() -> recovery.recover(101, RUN)).hasMessageContaining("QUEUED_HAS_EXECUTION_EVIDENCE");
        assertThat(state(101)).isEqualTo(queued);
        task(102, true, "v2");
        step(202, 102, 0, "LLM_DECISION", true);
        llm(302, 102, 202, "DECISION", 10, 3, "EXACT");
        jdbc.update("UPDATE agent_task SET input_tokens=1,output_tokens=2,total_tokens=3,token_usage_quality='EXACT' WHERE id=102");
        List<String> running = state(102);
        assertThatThrownBy(() -> recovery.recover(102, RUN)).hasMessageContaining("TASK_USAGE_CONFLICTS_WITH_RECORDED_CALLS");
        assertThat(state(102)).isEqualTo(running);
        task(103, true, "v1");
        jdbc.update("INSERT INTO agent_task_event VALUES (1032,103,3,'TASK_FAILED','{}','2026-09-11T23:59:32Z')");
        List<String> contradictoryEvent = state(103);
        assertThatThrownBy(() -> recovery.recover(103, RUN)).hasMessageContaining("NONTERMINAL_TASK_HAS_TERMINAL_PUBLICATION");
        assertThat(state(103)).isEqualTo(contradictoryEvent);
    }

    @Test
    void interruptionAfterMoreThanTwentyFiveDaysDoesNotInventOrOverflowCallDuration() throws Exception {
        task(101, true, "v2");
        step(201, 101, 0, "TOOL_CALL", true);
        tool(301, 101, 201, true);
        jdbc.update("UPDATE agent_task SET created_at='2026-01-01T00:00:00Z',started_at='2026-01-01T00:00:01Z' WHERE id=101");
        jdbc.update("UPDATE agent_step SET created_at='2026-01-01T00:00:01Z',started_at='2026-01-01T00:00:01Z' WHERE task_id=101");
        jdbc.update("UPDATE tool_call_log SET created_at='2026-01-01T00:00:01Z',started_at='2026-01-01T00:00:01Z' WHERE task_id=101");
        recovery.recover(101, RUN);
        assertThat(json.readTree(rowJson("agent_step", 201)).path("latency_ms").isNull()).isTrue();
        assertThat(json.readTree(rowJson("tool_call_log", 301)).path("latency_ms").isNull()).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_step SET error_code='ORDINARY_FAILURE' WHERE id=201"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tool_call_log SET error_code='ORDINARY_FAILURE' WHERE id=301"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE tool_call_log SET status='TIMEOUT' WHERE id=301"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void preservesUnknownCallQualityAndAcceptsConsistentPreviouslyPersistedUsage() throws Exception {
        task(101, true, "v2");
        step(201, 101, 0, "LLM_DECISION", true);
        llm(301, 101, 201, "DECISION", 10, 3, "EXACT");
        llm(302, 101, 201, "DECISION", null, null, "UNKNOWN");
        jdbc.update("UPDATE agent_task SET input_tokens=10,output_tokens=3,total_tokens=13,token_usage_quality='EXACT' WHERE id=101");
        String unknown = rowJson("llm_call_log", 302);
        recovery.recover(101, RUN);
        assertThat(taskJson(101).at("/recovery_metadata/previousTaskUsage/totalTokens").asInt()).isEqualTo(13);
        assertThat(taskJson(101).at("/recovery_metadata/previousTaskUsage/tokenUsageQuality").asText()).isEqualTo("EXACT");
        assertThat(taskJson(101).at("/recovery_metadata/recordedUsage/tokenUsageQuality").asText()).isEqualTo("EXACT");
        assertThat(taskJson(101).path("token_usage_quality").asText()).isEqualTo("UNKNOWN");
        assertThat(rowJson("llm_call_log", 302)).isEqualTo(unknown);
    }

    @Test
    void rowLockSeesCancellationCommittedBeforeRecoveryAndCommitsOnlyOneTerminalEvent() throws Exception {
        task(101, true, "v1");
        try (Connection cancellation = dataSource.getConnection(); var workers = Executors.newSingleThreadExecutor()) {
            cancellation.setAutoCommit(false);
            cancellation.createStatement().executeUpdate("UPDATE agent_task SET cancel_requested_at='2026-09-11T23:59:32Z',version=version+1 WHERE id=101 AND status='RUNNING'");
            CountDownLatch entered = new CountDownLatch(1);
            var pending = workers.submit(() -> { entered.countDown(); return recovery.recover(101, RUN); });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.commit();
            assertThat(pending.get(10, TimeUnit.SECONDS)).isTrue();
        }
        JsonNode task = taskJson(101);
        assertThat(task.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(task.path("termination_reason").asText()).isEqualTo("USER_CANCELLED");
        assertThat(task.path("error_code").isNull()).isTrue();
        assertThat(task.at("/recovery_metadata/reasonCode").asText()).isEqualTo("TASK_RESTART_INTERRUPTED");
        assertThat(recovery.recover(101, UUID.randomUUID().toString())).isFalse();
        assertThat(eventTypes(101)).containsExactly("TASK_CREATED", "TASK_STARTED", "TASK_CANCELLED");
    }

    @Test
    void defensivelyCancelsAnomalousLegacyQueuedCancellationWithoutRelaxingProductionSchema() throws Exception {
        task(101, false, "v1");
        String originalConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid='agent_task'::regclass AND conname='ck_agent_task_cancel_shape'
                """, String.class);
        jdbc.execute("ALTER TABLE agent_task DROP CONSTRAINT ck_agent_task_cancel_shape");
        try {
            jdbc.update("UPDATE agent_task SET cancel_requested_at='2026-09-11T23:59:32Z' WHERE id=101");
            recovery.recover(101, RUN);
            JsonNode task = taskJson(101);
            assertThat(task.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(task.path("termination_reason").asText()).isEqualTo("USER_CANCELLED");
            assertThat(task.path("error_code").isNull()).isTrue();
            assertThat(task.path("started_at").isNull()).isTrue();
            assertThat(task.at("/recovery_metadata/previousStatus").asText()).isEqualTo("QUEUED");
            assertThat(task.at("/recovery_metadata/reasonCode").asText()).isEqualTo("TASK_RESTART_DISPATCH_LOST");
            assertThat(task.at("/recovery_metadata/queuedCancellationAnomaly").asBoolean()).isTrue();
            assertThat(eventTypes(101)).containsExactly("TASK_CREATED", "TASK_CANCELLED");
        } finally {
            jdbc.update("UPDATE agent_task SET cancel_requested_at=NULL WHERE status='QUEUED'");
            jdbc.execute("ALTER TABLE agent_task ADD CONSTRAINT ck_agent_task_cancel_shape " + originalConstraint);
        }
    }

    @Test
    void existingAllTerminalStatusesAndTraceGapsRemainByteForByteUnchanged() throws Exception {
        int id = 101;
        for (String status : List.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")) {
            task(id, true, id % 2 == 0 ? "v1" : "v2");
            step(id + 100, id, 0, "LLM_DECISION", true);
            String reason = switch (status) {
                case "COMPLETED" -> "ANSWERED";
                case "FAILED" -> "SYSTEM_ERROR";
                case "CANCELLED" -> "USER_CANCELLED";
                default -> "DEADLINE_EXCEEDED";
            };
            jdbc.update("""
                    UPDATE agent_task SET status=?,phase=NULL,termination_reason=?,completed_at='2026-09-11T23:59:33Z',
                    final_answer=?,error_code=?,error_message=?,cancel_requested_at=CAST(? AS TIMESTAMPTZ) WHERE id=?
                    """, status, reason, status.equals("COMPLETED") ? "original final" : null,
                    status.equals("FAILED") ? "ORIGINAL_FAILURE" : null, status.equals("FAILED") ? "Original error" : null,
                    status.equals("CANCELLED") ? "2026-09-11T23:59:32Z" : null, id);
            List<String> before = state(id);
            assertThat(recovery.recover(id, RUN)).isFalse();
            assertThat(recovery.recover(id, UUID.randomUUID().toString())).isFalse();
            assertThat(state(id)).isEqualTo(before);
            id++;
        }
    }

    private void task(long id, boolean running, String snapshotVersion) {
        jdbc.update("""
                INSERT INTO agent_task (id,user_id,agent_id,client_request_id,request_fingerprint,status,phase,
                user_input,execution_snapshot,max_decision_turns,max_tool_calls,max_total_tokens,reserved_final_tokens,
                started_at,last_event_sequence,created_at,updated_at)
                VALUES (?,1101,2101,?,repeat('a',64),?,?,'Original input',CAST(? AS JSONB),6,4,8000,1000,
                CAST(? AS TIMESTAMPTZ),?,'2026-09-11T23:59:30Z','2026-09-11T23:59:31Z')
                """, id, "recovery-" + id, running ? "RUNNING" : "QUEUED", running ? "DECIDING" : null,
                "{\"snapshotVersion\":\"" + snapshotVersion + "\",\"agent\":{\"timeoutSeconds\":1},\"historical\":true}",
                running ? "2026-09-11T23:59:31Z" : null, running ? 2 : 1);
        jdbc.update("INSERT INTO agent_task_event VALUES (?,?,1,'TASK_CREATED','{\"status\":\"QUEUED\"}','2026-09-11T23:59:30Z')", id * 10, id);
        if (running) jdbc.update("INSERT INTO agent_task_event VALUES (?,?,2,'TASK_STARTED','{\"status\":\"RUNNING\",\"phase\":\"DECIDING\"}','2026-09-11T23:59:31Z')", id * 10 + 1, id);
    }

    private void step(long id, long taskId, int index, String type, boolean running) {
        jdbc.update("""
                INSERT INTO agent_step (id,task_id,step_index,step_type,status,title,summary,started_at,ended_at,latency_ms,created_at)
                VALUES (?,?,?,?,?,'Original step','{"evidence":"preserve"}', '2026-09-11T23:59:31Z',CAST(? AS TIMESTAMPTZ),?,'2026-09-11T23:59:31Z')
                """, id, taskId, index, type, running ? "RUNNING" : "SUCCESS",
                running ? null : "2026-09-11T23:59:32Z", running ? null : 1000);
    }

    private void llm(long id, long taskId, long stepId, String type, Integer input, Integer output, String quality) {
        jdbc.update("""
                INSERT INTO llm_call_log (id,task_id,step_id,call_type,provider,requested_model,request_snapshot,response_text,
                input_tokens,output_tokens,total_tokens,usage_quality,latency_ms,status,created_at)
                VALUES (?,?,?,?,'controlled','historical-model','{}','Persisted final response',?,?,?,?,100,'SUCCESS','2026-09-11T23:59:32Z')
                """, id, taskId, stepId, type, input, output, input == null ? null : input + output, quality);
    }

    private void tool(long id, long taskId, long stepId, boolean running) {
        jdbc.update("""
                INSERT INTO tool_call_log (id,task_id,step_id,tool_id,tool_code,tool_name,arguments,result,status,latency_ms,started_at,finished_at,created_at)
                VALUES (?,?,?,270000000000000001,'order_query','Order Query','{}',CAST(? AS JSONB),?,?,'2026-09-11T23:59:31Z',CAST(? AS TIMESTAMPTZ),'2026-09-11T23:59:31Z')
                """, id, taskId, stepId, running ? null : "{\"originalResult\":true}", running ? "RUNNING" : "SUCCESS",
                running ? null : 1000, running ? null : "2026-09-11T23:59:32Z");
    }

    private List<String> state(long taskId) {
        return List.of(rowJson("agent_task", taskId), rows("agent_step", taskId), rows("tool_call_log", taskId),
                rows("llm_call_log", taskId), rows("agent_task_event", taskId));
    }

    private String rowJson(String table, long id) {
        return jdbc.queryForObject("SELECT row_to_json(t)::text FROM " + table + " t WHERE id=?", String.class, id);
    }

    private String rows(String table, long taskId) {
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]')::text FROM " + table + " t WHERE task_id=?", String.class, taskId);
    }

    private JsonNode taskJson(long id) throws Exception { return json.readTree(rowJson("agent_task", id)); }
    private List<String> eventTypes(long taskId) {
        return jdbc.queryForList("SELECT event_type FROM agent_task_event WHERE task_id=? ORDER BY sequence_no", String.class, taskId);
    }

    private void installFault(String stage) {
        String table = switch (stage) {
            case "step" -> "agent_step";
            case "tool" -> "tool_call_log";
            case "event" -> "agent_task_event";
            default -> "agent_task";
        };
        String guard = switch (stage) {
            case "task", "zero-row" -> "IF NEW.recovery_metadata IS NULL THEN RETURN NEW; END IF; ";
            case "sequence" -> "IF NEW.last_event_sequence = OLD.last_event_sequence THEN RETURN NEW; END IF; ";
            default -> "";
        };
        jdbc.execute("CREATE FUNCTION recovery_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " + guard
                + (stage.equals("zero-row") ? "RETURN NULL;" : "RAISE EXCEPTION 'controlled recovery write failure';") + " END $$");
        jdbc.execute("CREATE TRIGGER recovery_fault BEFORE " + (stage.equals("event") ? "INSERT" : "UPDATE")
                + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION recovery_test_fault()");
    }

    @TestConfiguration
    @EnableTransactionManagement
    @Import({TaskRecoveryTransactionService.class, TaskEventAppender.class})
    static class DatabaseConfiguration {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getProperty("agentflow.postgres.url"),
                    System.getProperty("agentflow.postgres.user"), System.getProperty("agentflow.postgres.password", ""));
        }
        @Bean(initMethod = "migrate") Flyway flyway(DataSource source) { return Flyway.configure().dataSource(source).load(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC); }
        @Bean @DependsOn("flyway") SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean MapperFactoryBean<TaskRecoveryMapper> recoveryMapper(SqlSessionFactory factory) {
            return mapper(TaskRecoveryMapper.class, factory);
        }
        @Bean MapperFactoryBean<LlmCallLogMapper> llmCallMapper(SqlSessionFactory factory) {
            return mapper(LlmCallLogMapper.class, factory);
        }
        @Bean MapperFactoryBean<AgentTaskEventMapper> eventMapper(SqlSessionFactory factory) {
            return mapper(AgentTaskEventMapper.class, factory);
        }
        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionFactory factory) {
            var bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionFactory(factory);
            return bean;
        }
    }
}
