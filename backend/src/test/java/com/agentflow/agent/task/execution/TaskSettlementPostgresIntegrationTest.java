package com.agentflow.agent.task.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskTerminationReason;
import com.agentflow.agent.task.model.TokenUsageQuality;
import com.agentflow.agent.task.recovery.TaskExecutionAdmission;
import com.agentflow.agent.task.repository.AgentTaskEventMapper;
import com.agentflow.agent.task.repository.AgentTaskMapper;
import com.agentflow.agent.task.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real PostgreSQL/transaction/sequence fixtures; external business work is absent by design. */
@SpringJUnitConfig(TaskSettlementPostgresIntegrationTest.DatabaseConfiguration.class)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class TaskSettlementPostgresIntegrationTest {
    static final Instant OBSERVED = Instant.parse("2026-09-11T23:59:32Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AgentTaskLifecycleTransactionService lifecycle;
    @Autowired AgentTaskQueryService query;
    @Autowired TaskExecutionAdmission lifecycleGate;
    final TaskExecutionOutcome outcome = TaskExecutionOutcome.completed("Original observed answer", TaskTerminationReason.ANSWERED,
            2, 1, new TaskTokenUsage(31, 19, TokenUsageQuality.EXACT), null);
    TaskExecutionAdmission gate;
    List<Long> waits;

    @BeforeEach void fixture() {
        jdbc.execute("DROP FUNCTION IF EXISTS settlement_test_fault() CASCADE");
        jdbc.execute("DROP SEQUENCE IF EXISTS settlement_attempts");
        jdbc.execute("TRUNCATE app_user CASCADE");
        jdbc.update("INSERT INTO app_user (id,username,email,password_hash,display_name) VALUES (1101,'settlement','settlement@example.test','hash','Settlement fixture')");
        jdbc.update("INSERT INTO agent_app (id,user_id,name,system_prompt,model_provider,model_name,max_steps,max_tool_calls,max_tokens,timeout_seconds,status) VALUES (2101,1101,'Settlement','Frozen','openai-compatible','historical',6,4,8000,120,'ACTIVE')");
        task(101, true);
        gate = new TaskExecutionAdmission(); gate.open();
        lifecycleGate.open();
        waits = new ArrayList<>();
    }

    @Test void B05_retriesTwoRealSerializationFailuresAndPreservesObservedUsageTimeAndEvents() {
        fault(false, "40001", 2);
        assertThat(service(waits::add).settle(101, outcome, OBSERVED)).isTrue();
        assertThat(waits).containsExactly(100L, 500L);
        assertThat(attempts()).isEqualTo(3);
        assertCompleted();
    }

    @Test void B06_rollsBackAnswerEventsAndSequenceBeforeRetryingTheSameTransaction() {
        fault(true, "40001", 1);
        var service = service(millis -> {
            waits.add(millis);
            assertThat(query.findById(101).getStatus()).isEqualTo("RUNNING");
            assertThat(query.findById(101).getFinalAnswer()).isNull();
            assertThat(events()).containsExactly("TASK_CREATED", "TASK_STARTED");
            assertThat(query.findById(101).getLastEventSequence()).isEqualTo(2);
        });
        assertThat(service.settle(101, outcome, OBSERVED)).isTrue();
        assertThat(attempts()).isEqualTo(2);
        assertCompleted();
    }

    @Test void B06_readsBackARealCommitAfterTheCallingBoundaryLosesItsResponse() {
        AgentTaskLifecycleTransactionService boundary = mock(AgentTaskLifecycleTransactionService.class);
        AtomicInteger writes = new AtomicInteger();
        when(boundary.settleObserved(eq(101L), any(), any())).thenAnswer(call -> {
            assertThat(lifecycle.settleObserved(101, call.getArgument(1), call.getArgument(2))).isTrue();
            writes.incrementAndGet();
            throw new DataAccessResourceFailureException("Controlled COMMIT response loss after real transaction returned");
        });
        assertThat(new TaskSettlementService(boundary, query, gate, waits::add).settle(101, outcome, OBSERVED)).isTrue();
        assertThat(writes).hasValue(1);
        assertThat(waits).isEmpty();
        assertCompleted();
    }

    @Test void B07_cancelCommittedDuringBackoffWinsWithoutChangingObservedUsageOrTime() {
        fault(false, "40001", 1);
        assertThat(service(millis -> lifecycle.requestCancellation(1101, 101)).settle(101, outcome, OBSERVED)).isTrue();
        AgentTask task = query.findById(101);
        assertThat(task.getStatus()).isEqualTo("CANCELLED");
        assertThat(task.getCompletedAt().toInstant()).isEqualTo(OBSERVED);
        assertThat(task.getCancelRequestedAt().toInstant()).isAfter(OBSERVED);
        assertThat(task.getUpdatedAt()).isAfterOrEqualTo(task.getCancelRequestedAt());
        assertThat(task.getTotalTokens()).isEqualTo(50);
        assertThat(task.getTokenUsageQuality()).isEqualTo("EXACT");
        assertThat(events()).containsExactly("TASK_CREATED", "TASK_STARTED", "TASK_CANCELLED");
    }

    @Test void B07_lockArbitrationObservesConcurrentPersistentCancelBeforePublishingAnswer() throws Exception {
        try (Connection cancel = dataSource.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            cancel.setAutoCommit(false);
            cancel.createStatement().executeUpdate("UPDATE agent_task SET cancel_requested_at='2026-09-11T23:59:33Z',version=version+1 WHERE id=101 AND status='RUNNING'");
            CountDownLatch entered = new CountDownLatch(1);
            var result = worker.submit(() -> { entered.countDown(); return service(waits::add).settle(101, outcome, OBSERVED); });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            cancel.commit();
            assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(query.findById(101).getStatus()).isEqualTo("CANCELLED");
        assertThat(query.findById(101).getFinalAnswer()).isNull();
        assertThat(events()).containsExactly("TASK_CREATED", "TASK_STARTED", "TASK_CANCELLED");
    }

    @Test void B07_anotherCommittedTerminalIsAcceptedWithoutRepublishingTheOriginalResult() {
        fault(false, "40001", 1);
        assertThat(service(millis -> lifecycle.timeOut(101, TaskExecutionOutcome.timedOut())).settle(101, outcome, OBSERVED)).isTrue();
        assertThat(query.findById(101).getStatus()).isEqualTo("TIMED_OUT");
        assertThat(query.findById(101).getTotalTokens()).isZero();
        assertThat(query.findById(101).getTokenUsageQuality()).isEqualTo("UNKNOWN");
        assertThat(events()).containsExactly("TASK_CREATED", "TASK_STARTED", "TASK_TIMED_OUT");
    }

    @ParameterizedTest @ValueSource(strings = {"40001", "23514"})
    void B08_realPersistentOrPermanentDatabaseFailureExitsBoundedlyWithoutClaimingATerminal(String state) {
        fault(false, state, 999);
        assertThat(service(waits::add).settle(101, outcome, OBSERVED)).isFalse();
        assertThat(attempts()).isEqualTo(state.equals("40001") ? 3 : 1);
        assertThat(waits).hasSize(state.equals("40001") ? 2 : 0);
        assertThat(query.findById(101).getStatus()).isEqualTo("RUNNING");
        assertThat(query.findById(101).getCompletedAt()).isNull();
        assertThat(events()).containsExactly("TASK_CREATED", "TASK_STARTED");
        assertThat(gate.diagnostic()).contains("TASK_SETTLEMENT_PERSIST_FAILED:task=101:attempts=" + attempts());
        assertThatThrownBy(gate::open).isInstanceOf(IllegalStateException.class);
    }

    @Test void B08_alreadyCreatedQueuedWorkGetsDurableRejectionDespiteClosedAdmission() {
        task(102, false);
        gate.close("runtime settlement degraded");
        assertThat(service(waits::add).rejectDispatch(102)).isTrue();
        assertThat(query.findById(102).getStatus()).isEqualTo("FAILED");
        assertThat(query.findById(102).getErrorCode()).isEqualTo("TASK_DISPATCH_REJECTED");
        assertThat(jdbc.queryForList("SELECT event_type FROM agent_task_event WHERE task_id=102 ORDER BY sequence_no", String.class))
                .containsExactly("TASK_CREATED", "TASK_FAILED");
    }

    private TaskSettlementService service(TaskSettlementService.Sleeper sleeper) {
        return new TaskSettlementService(lifecycle, query, gate, sleeper);
    }
    private void assertCompleted() {
        AgentTask task = query.findById(101);
        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getCompletedAt().toInstant()).isEqualTo(OBSERVED);
        assertThat(task.getTotalTokens()).isEqualTo(50);
        assertThat(task.getTokenUsageQuality()).isEqualTo("EXACT");
        assertThat(task.getDecisionTurnsUsed()).isEqualTo(2);
        assertThat(task.getToolCallsUsed()).isEqualTo(1);
        assertThat(task.getFinalAnswer()).isEqualTo(outcome.finalAnswer());
        assertThat(events()).containsExactly("TASK_CREATED", "TASK_STARTED", "ANSWER_CHUNK", "TASK_COMPLETED");
        assertThat(jdbc.queryForList("SELECT sequence_no FROM agent_task_event WHERE task_id=101 ORDER BY sequence_no", Long.class))
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(task.getLastEventSequence()).isEqualTo(4);
    }
    private long attempts() { return jdbc.queryForObject("SELECT last_value FROM settlement_attempts", Long.class); }
    private List<String> events() {
        return jdbc.queryForList("SELECT event_type FROM agent_task_event WHERE task_id=101 ORDER BY sequence_no", String.class);
    }
    private void task(long id, boolean running) {
        jdbc.update("""
                INSERT INTO agent_task (id,user_id,agent_id,client_request_id,request_fingerprint,status,phase,user_input,
                execution_snapshot,max_decision_turns,max_tool_calls,max_total_tokens,reserved_final_tokens,
                started_at,last_event_sequence,created_at,updated_at)
                VALUES (?,1101,2101,?,repeat('a',64),?,?,'Original input','{"snapshotVersion":"v1","agent":{"timeoutSeconds":1}}',
                6,4,8000,1000,CAST(? AS TIMESTAMPTZ),?,'2026-09-11T23:59:30Z','2026-09-11T23:59:31Z')
                """, id, "settlement-" + id, running ? "RUNNING" : "QUEUED", running ? "DECIDING" : null,
                running ? "2026-09-11T23:59:31Z" : null, running ? 2 : 1);
        jdbc.update("INSERT INTO agent_task_event VALUES (?,?,1,'TASK_CREATED','{\"status\":\"QUEUED\"}','2026-09-11T23:59:30Z')", id * 10, id);
        if (running) jdbc.update("INSERT INTO agent_task_event VALUES (?,?,2,'TASK_STARTED','{\"status\":\"RUNNING\",\"phase\":\"DECIDING\"}','2026-09-11T23:59:31Z')", id * 10 + 1, id);
    }
    private void fault(boolean event, String state, int failures) {
        jdbc.execute("CREATE SEQUENCE settlement_attempts");
        String guard = event ? "NEW.event_type = 'TASK_COMPLETED'" : "NEW.status <> OLD.status AND NEW.status IN ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')";
        jdbc.execute("CREATE FUNCTION settlement_test_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + guard
                + " THEN IF nextval('settlement_attempts') <= " + failures + " THEN RAISE EXCEPTION USING ERRCODE='" + state
                + "',MESSAGE='controlled settlement fault'; END IF; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER settlement_fault BEFORE " + (event ? "INSERT ON agent_task_event" : "UPDATE ON agent_task")
                + " FOR EACH ROW EXECUTE FUNCTION settlement_test_fault()");
    }

    @TestConfiguration @EnableTransactionManagement
    @Import({AgentTaskLifecycleTransactionService.class, AgentTaskQueryService.class, TaskEventAppender.class})
    static class DatabaseConfiguration {
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(System.getProperty("agentflow.postgres.url"),
                    System.getProperty("agentflow.postgres.user"), System.getProperty("agentflow.postgres.password", ""));
            var properties = new java.util.Properties(); properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "10");
            source.setConnectionProperties(properties); return source;
        }
        @Bean(initMethod = "migrate") Flyway flyway(DataSource source) { return Flyway.configure().dataSource(source).load(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC); }
        @Bean TaskExecutionAdmission admission() { return new TaskExecutionAdmission(); }
        @Bean @DependsOn("flyway") SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var configuration = new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true);
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean MapperFactoryBean<AgentTaskMapper> taskMapper(SqlSessionFactory factory) { return mapper(AgentTaskMapper.class, factory); }
        @Bean MapperFactoryBean<AgentTaskEventMapper> eventMapper(SqlSessionFactory factory) { return mapper(AgentTaskEventMapper.class, factory); }
        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionFactory factory) {
            var bean = new MapperFactoryBean<>(type); bean.setSqlSessionFactory(factory); return bean;
        }
    }
}
