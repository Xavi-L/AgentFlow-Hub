package com.agentflow.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.agent.task.dispatch.TaskDispatchRejectedException;
import com.agentflow.agent.task.dispatch.TaskDispatcher;
import com.agentflow.agent.task.execution.TaskExecutionDelegate;
import com.agentflow.agent.task.execution.TaskExecutionOutcome;
import com.agentflow.agent.task.execution.TaskExecutionRequest;
import com.agentflow.agent.task.execution.TaskRunner;
import com.agentflow.agent.task.execution.TaskTokenUsage;
import com.agentflow.agent.task.model.AgentTask;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.model.TaskStatus;
import com.agentflow.agent.task.model.TaskTerminationReason;
import com.agentflow.agent.task.model.TokenUsageQuality;
import com.agentflow.agent.task.service.AgentTaskApplicationService;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.task.service.AgentTaskQueryService;
import com.agentflow.agent.task.service.CreateAgentTaskCommand;
import com.agentflow.agent.task.service.TaskEventAppender;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Focused real-PostgreSQL evidence for the M4C races that mocks cannot prove. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AgentTaskPostgresIntegrationTest.TaskTestConfiguration.class)
@EnabledIfSystemProperty(named = "agentflow.postgres.integration", matches = "true")
class AgentTaskPostgresIntegrationTest {
    private static final long USER_ID = 1101L;
    private static final long OTHER_USER_ID = 1102L;
    private static final long AGENT_ID = 2101L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredProperty("agentflow.postgres.url"));
        registry.add("spring.datasource.username", () -> requiredProperty("agentflow.postgres.user"));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.postgres.password", ""));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "16");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("mybatis-plus.configuration.log-impl", () ->
                "org.apache.ibatis.logging.nologging.NoLoggingImpl");
        registry.add("logging.level.com.agentflow", () -> "WARN");
        registry.add("agentflow.task.execution.mode", () -> "scripted");
        registry.add("agentflow.security.jwt.secret-base64", () ->
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AgentTaskApplicationService applicationService;
    @Autowired
    private AgentTaskLifecycleTransactionService lifecycleTransactions;
    @Autowired
    private AgentTaskQueryService queryService;
    @Autowired
    private TaskRunner taskRunner;
    @Autowired
    private ConcurrentEventAppendTransaction concurrentEventAppend;
    @Autowired
    private ScriptedTaskExecutionDelegate scriptedDelegate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentTaskSnapshotResolver snapshotResolver;
    @MockBean
    private TaskDispatcher taskDispatcher;

    @BeforeEach
    void resetDatabaseAndFakes() {
        jdbc.update("DELETE FROM tool_call_log");
        jdbc.update("DELETE FROM rag_retrieval_hit");
        jdbc.update("DELETE FROM rag_retrieval_log");
        jdbc.update("DELETE FROM llm_call_log");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_task_event");
        jdbc.update("DELETE FROM agent_task");
        jdbc.update("DELETE FROM agent_tool_binding");
        jdbc.update("DELETE FROM agent_knowledge_binding");
        jdbc.update("DELETE FROM agent_app");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("""
                INSERT INTO app_user (id, username, email, password_hash, display_name)
                VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                """,
                USER_ID, "v38-user", "v38-user@example.test", "hash", "V38 User",
                OTHER_USER_ID, "v38-other", "v38-other@example.test", "hash", "V38 Other"
        );
        jdbc.update("""
                INSERT INTO agent_app (
                    id, user_id, name, system_prompt, model_provider, model_name,
                    max_steps, max_tool_calls, max_tokens, timeout_seconds, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                AGENT_ID, USER_ID, "V38 Agent", "Use frozen evidence.",
                "openai-compatible", "test-model", 6, 4, 8000, 120, "ACTIVE"
        );
        reset(snapshotResolver, taskDispatcher);
        when(snapshotResolver.resolve(USER_ID, AGENT_ID)).thenReturn(snapshot(AGENT_ID));
        scriptedDelegate.reset();
    }

    @Test
    void shouldApplyV1ThroughV18AndEnforceOwnerStatusAndJsonConstraints() throws Exception {
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '18'
                  AND script = 'V18__create_agent_task_and_event.sql'
                  AND success
                """,
                Integer.class
        )).isEqualTo(1);

        AgentTask created = create("migration-contract", "  preserve this input  ");
        AgentTask persisted = queryService.findById(created.getId());
        assertThat(persisted.getStatus()).isEqualTo("QUEUED");
        assertThat(persisted.getUserInput()).isEqualTo("  preserve this input  ");
        assertThat(persisted.getReservedFinalTokens()).isEqualTo(2000);
        assertThat(persisted.getLastEventSequence()).isEqualTo(1L);
        assertThat(eventTypes(created.getId())).containsExactly("TASK_CREATED");
        assertThat(objectMapper.readTree(persisted.getExecutionSnapshot())
                .path("agent").path("maxTotalTokens").asInt()).isEqualTo(8000);

        jdbc.update("UPDATE agent_app SET max_tokens = 9000 WHERE id = ?", AGENT_ID);
        AgentTask frozen = queryService.findById(created.getId());
        assertThat(frozen.getMaxTotalTokens()).isEqualTo(8000);
        assertThat(objectMapper.readTree(frozen.getExecutionSnapshot())
                .path("agent").path("maxTotalTokens").asInt()).isEqualTo(8000);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE agent_task SET user_id = ? WHERE id = ?", OTHER_USER_ID, created.getId()
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE agent_task SET status = 'TIMEOUT' WHERE id = ?", created.getId()
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE agent_task SET execution_snapshot = '[]'::jsonb WHERE id = ?", created.getId()
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepSequentialAndConcurrentCreationIdempotent() throws Exception {
        AgentTask first = create("same-key", "same payload");
        AgentTask retry = create("same-key", "same payload");
        assertThat(retry.getId()).isEqualTo(first.getId());
        verify(taskDispatcher, times(1)).dispatch(first.getId());

        assertThatThrownBy(() -> create("same-key", "different payload"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TASK_IDEMPOTENCY_CONFLICT));

        clearInvocations(taskDispatcher);
        List<AgentTask> concurrent = concurrently(
                () -> create("concurrent-key", "concurrent payload"),
                () -> create("concurrent-key", "concurrent payload")
        );
        assertThat(concurrent).extracting(AgentTask::getId).containsOnly(concurrent.getFirst().getId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_task WHERE client_request_id = 'concurrent-key'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_task_event WHERE task_id = ?",
                Integer.class,
                concurrent.getFirst().getId()
        )).isEqualTo(1);
        verify(taskDispatcher, times(1)).dispatch(concurrent.getFirst().getId());
    }

    @Test
    void shouldAllocateContinuousEventSequencesUnderPostgresConcurrency() throws Exception {
        AgentTask task = create("event-race", "event race");
        List<Callable<Long>> appends = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            appends.add(() -> concurrentEventAppend.appendPhaseEvent(task.getId()));
        }

        List<Long> allocated = concurrently(appends);

        assertThat(allocated).containsExactlyInAnyOrder(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(jdbc.queryForList(
                "SELECT sequence_no FROM agent_task_event WHERE task_id = ? ORDER BY sequence_no",
                Long.class,
                task.getId()
        )).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(jdbc.queryForObject(
                "SELECT last_event_sequence FROM agent_task WHERE id = ?",
                Long.class,
                task.getId()
        )).isEqualTo(9L);
    }

    @Test
    void shouldLetOnlyOneRunnerClaimAndCompleteOutsideTransactions() throws Exception {
        ObjectNode citation = objectMapper.createObjectNode().put("source", "scripted");
        scriptedDelegate.use(request -> TaskExecutionOutcome.completed(
                "scripted answer",
                TaskTerminationReason.ANSWERED,
                1,
                0,
                new TaskTokenUsage(10, 5, TokenUsageQuality.EXACT),
                objectMapper.createArrayNode().add(citation)
        ));
        AgentTask task = create("runner-race", "raw runner input");

        concurrently(
                () -> {
                    taskRunner.run(task.getId());
                    return null;
                },
                () -> {
                    taskRunner.run(task.getId());
                    return null;
                }
        );

        AgentTask completed = queryService.findById(task.getId());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getPhase()).isNull();
        assertThat(completed.getFinalAnswer()).isEqualTo("scripted answer");
        assertThat(completed.getTotalTokens()).isEqualTo(15);
        assertThat(eventTypes(task.getId())).containsExactly(
                "TASK_CREATED", "TASK_STARTED", "PHASE_CHANGED", "TASK_COMPLETED"
        );
        assertThat(scriptedDelegate.invocations()).isEqualTo(1);
        assertThat(scriptedDelegate.sawDatabaseTransaction()).isFalse();
    }

    @Test
    void shouldCompensateRejectedAfterCommitDispatchWithoutLeavingQueuedTask() {
        doThrow(new TaskDispatchRejectedException("queue full", new RuntimeException()))
                .when(taskDispatcher).dispatch(anyLong());

        AgentTask task = create("dispatch-rejected", "dispatch rejected");

        AgentTask failed = queryService.findById(task.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getTerminationReason()).isEqualTo("SYSTEM_ERROR");
        assertThat(failed.getErrorCode()).isEqualTo("TASK_DISPATCH_REJECTED");
        assertThat(eventTypes(task.getId())).containsExactly("TASK_CREATED", "TASK_FAILED");
    }

    @Test
    void shouldHandleQueuedAndRunningCancellationIdempotently() {
        AgentTask queued = create("queued-cancel", "cancel before claim");
        AgentTask cancelled = lifecycleTransactions.requestCancellation(USER_ID, queued.getId());
        AgentTask cancelledAgain = lifecycleTransactions.requestCancellation(USER_ID, queued.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelledAgain.getStatus()).isEqualTo("CANCELLED");
        assertThat(eventTypes(queued.getId())).containsExactly("TASK_CREATED", "TASK_CANCELLED");

        AgentTask running = create("running-cancel", "cancel after claim");
        assertThat(lifecycleTransactions.claim(running.getId())).isNotNull();
        AgentTask requested = lifecycleTransactions.requestCancellation(USER_ID, running.getId());
        assertThat(requested.getStatus()).isEqualTo("RUNNING");
        assertThat(requested.getCancelRequestedAt()).isNotNull();
        assertThat(eventTypes(running.getId())).containsExactly("TASK_CREATED", "TASK_STARTED");
        assertThat(lifecycleTransactions.finishCancellation(
                running.getId(),
                TaskExecutionOutcome.cancelled()
        )).isTrue();
        assertThat(eventTypes(running.getId())).containsExactly(
                "TASK_CREATED", "TASK_STARTED", "TASK_CANCELLED"
        );
    }

    @Test
    void shouldAllowOnlyOneOfCompleteTimeoutAndCancelToWin() throws Exception {
        AgentTask task = create("terminal-race", "terminal race");
        assertThat(lifecycleTransactions.claim(task.getId())).isNotNull();

        List<Boolean> winners = concurrently(
                () -> lifecycleTransactions.complete(
                        task.getId(),
                        TaskExecutionOutcome.completed("race answer")
                ),
                () -> lifecycleTransactions.timeOut(task.getId(), TaskExecutionOutcome.timedOut()),
                () -> {
                    lifecycleTransactions.requestCancellation(USER_ID, task.getId());
                    return lifecycleTransactions.finishCancellation(
                            task.getId(),
                            TaskExecutionOutcome.cancelled()
                    );
                }
        );

        assertThat(winners).containsOnlyOnce(true);
        AgentTask terminal = queryService.findById(task.getId());
        assertThat(terminal.getStatus()).isIn("COMPLETED", "TIMED_OUT", "CANCELLED");
        assertThat(terminal.getPhase()).isNull();
        assertThat(terminal.getCompletedAt()).isNotNull();
        assertThat(eventTypes(task.getId()).stream()
                .filter(type -> Set.of(
                        "TASK_COMPLETED", "TASK_TIMED_OUT", "TASK_CANCELLED"
                ).contains(type))
                .count()).isEqualTo(1);
    }

    @Test
    void shouldPersistScriptedFailureTimeoutAndObservedCancellation() throws Exception {
        scriptedDelegate.use(request -> TaskExecutionOutcome.failed("SCRIPTED_FAILURE", "Scripted failure"));
        AgentTask failed = create("script-failed", "fail");
        taskRunner.run(failed.getId());
        assertThat(queryService.findById(failed.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(eventTypes(failed.getId()).getLast()).isEqualTo("TASK_FAILED");

        scriptedDelegate.use(request -> TaskExecutionOutcome.timedOut());
        AgentTask timedOut = create("script-timeout", "timeout");
        taskRunner.run(timedOut.getId());
        AgentTask timedOutState = queryService.findById(timedOut.getId());
        assertThat(timedOutState.getStatus()).isEqualTo("TIMED_OUT");
        assertThat(timedOutState.getTerminationReason()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(eventTypes(timedOut.getId()).getLast()).isEqualTo("TASK_TIMED_OUT");

        CountDownLatch delegateEntered = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        scriptedDelegate.use(request -> {
            delegateEntered.countDown();
            if (!releaseDelegate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test delegate was not released");
            }
            return TaskExecutionOutcome.cancelled();
        });
        AgentTask cancelling = create("script-cancel", "cancel");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> runner = executor.submit(() -> taskRunner.run(cancelling.getId()));
            assertThat(delegateEntered.await(5, TimeUnit.SECONDS)).isTrue();
            AgentTask requested = lifecycleTransactions.requestCancellation(USER_ID, cancelling.getId());
            assertThat(requested.getCancelRequestedAt()).isNotNull();
            releaseDelegate.countDown();
            runner.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(queryService.findById(cancelling.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(eventTypes(cancelling.getId()).getLast()).isEqualTo("TASK_CANCELLED");
        assertThat(scriptedDelegate.sawDatabaseTransaction()).isFalse();
    }

    private AgentTask create(String key, String input) {
        return applicationService.createTask(new CreateAgentTaskCommand(USER_ID, AGENT_ID, key, input));
    }

    private List<String> eventTypes(long taskId) {
        return jdbc.queryForList(
                "SELECT event_type FROM agent_task_event WHERE task_id = ? ORDER BY sequence_no",
                String.class,
                taskId
        );
    }

    @SafeVarargs
    private static <T> List<T> concurrently(Callable<T>... calls) throws Exception {
        return concurrently(List.of(calls));
    }

    private static <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CyclicBarrier barrier = new CyclicBarrier(calls.size());
        try {
            List<Future<T>> futures = calls.stream()
                    .map(call -> executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return call.call();
                    }))
                    .toList();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static AgentTaskExecutionSnapshot snapshot(long agentId) {
        return new AgentTaskExecutionSnapshot(
                "agent-task-snapshot-v1",
                new AgentTaskExecutionSnapshot.AgentSnapshot(
                        Long.toString(agentId), "Use frozen evidence.", "ACTIVE", 6, 4, 8000, 120
                ),
                new AgentTaskExecutionSnapshot.RuntimeSnapshot(
                        "agent-decision-json-v1", "agent-runtime-rules-v1", "a925d6b"
                ),
                new AgentTaskExecutionSnapshot.ChatModelSnapshot(
                        "openai-compatible-default", "openai-compatible", "test-model",
                        new BigDecimal("0.2"), new BigDecimal("0.8"), 32768, true
                ),
                new AgentTaskExecutionSnapshot.RetrievalSnapshot(
                        List.of(), 5, new BigDecimal("0.2"), false
                ),
                List.of()
        );
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test system property " + name);
        }
        return value;
    }

    @TestConfiguration
    static class TaskTestConfiguration {
        @Bean
        @Primary
        ScriptedTaskExecutionDelegate scriptedTaskExecutionDelegate() {
            return new ScriptedTaskExecutionDelegate();
        }

        @Bean
        ConcurrentEventAppendTransaction concurrentEventAppendTransaction(
                TaskEventAppender eventAppender,
                ObjectMapper objectMapper
        ) {
            return new ConcurrentEventAppendTransaction(eventAppender, objectMapper);
        }
    }

    static class ConcurrentEventAppendTransaction {
        private final TaskEventAppender eventAppender;
        private final ObjectMapper objectMapper;

        ConcurrentEventAppendTransaction(TaskEventAppender eventAppender, ObjectMapper objectMapper) {
            this.eventAppender = eventAppender;
            this.objectMapper = objectMapper;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public long appendPhaseEvent(long taskId) {
            ObjectNode payload = objectMapper.createObjectNode().put("phase", "DECIDING");
            return eventAppender.append(taskId, TaskEventType.PHASE_CHANGED, payload);
        }
    }

    static class ScriptedTaskExecutionDelegate implements TaskExecutionDelegate {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicBoolean sawDatabaseTransaction = new AtomicBoolean();
        private volatile Script script = request -> TaskExecutionOutcome.failed(
                "TASK_INTERNAL_ERROR", "No test script configured"
        );

        void use(Script script) {
            this.script = script;
        }

        void reset() {
            invocations.set(0);
            sawDatabaseTransaction.set(false);
            script = request -> TaskExecutionOutcome.failed(
                    "TASK_INTERNAL_ERROR", "No test script configured"
            );
        }

        int invocations() {
            return invocations.get();
        }

        boolean sawDatabaseTransaction() {
            return sawDatabaseTransaction.get();
        }

        @Override
        public TaskExecutionOutcome execute(TaskExecutionRequest request) throws Exception {
            invocations.incrementAndGet();
            sawDatabaseTransaction.compareAndSet(
                    false,
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            return script.execute(request);
        }
    }

    @FunctionalInterface
    interface Script {
        TaskExecutionOutcome execute(TaskExecutionRequest request) throws Exception;
    }
}
