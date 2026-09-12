package com.agentflow.agent.task.recovery;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Startup-only bounded scan. Deliberately has no Dispatcher or provider dependencies. */
@Component("taskExecutionHealthIndicator")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TaskRecoveryStartupCoordinator implements ApplicationRunner, HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryStartupCoordinator.class);
    private final AtomicBoolean started = new AtomicBoolean();
    private final TaskRecoveryProperties properties;
    private final TaskExecutionProcessLock lock;
    private final TaskExecutionAdmission admission;
    private final TaskRecoveryMapper mapper;
    private final TaskRecoveryTransactionService transactions;
    public TaskRecoveryStartupCoordinator(TaskRecoveryProperties properties, TaskExecutionProcessLock lock,
            TaskExecutionAdmission admission, TaskRecoveryMapper mapper, TaskRecoveryTransactionService transactions) {
        this.properties = properties;
        this.lock = lock;
        this.admission = admission;
        this.mapper = mapper;
        this.transactions = transactions;
    }
    @Override
    public void run(ApplicationArguments args) {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Recovery is startup-only");
        admission.close("RECOVERY_STARTING");
        String stage = "CANDIDATE_READ";
        Long taskId = null;
        String runId = UUID.randomUUID().toString();
        try {
            lock.requireHeld();
            if (properties.getMode() == TaskRecoveryProperties.Mode.DISABLED) {
                if (mapper.hasCandidates()) {
                    admission.close("DISABLED_WITH_LEGACY_TASKS");
                    log.error("TASK_EXECUTION_NOT_READY stage=DISABLED_WITH_LEGACY_TASKS; stop all old JVMs, record cold cutover, then explicitly enable CONTROLLED_SINGLE_HOST");
                    return;
                }
            } else {
                long cursor = 0;
                while (true) {
                    stage = "CANDIDATE_READ";
                    taskId = null;
                    var candidates = mapper.selectCandidateIds(cursor, properties.getBatchSize());
                    if (candidates.isEmpty()) break;
                    for (long id : candidates) {
                        taskId = id;
                        stage = "TASK_SETTLEMENT";
                        transactions.recover(id, runId);
                        cursor = id;
                    }
                }
                stage = "FINAL_CANDIDATE_CHECK";
                taskId = null;
                if (mapper.hasCandidates()) throw new IllegalStateException("Unsettled candidates remain");
            }
            lock.requireHeld();
            admission.open();
            log.info("Task execution READY mode={} recoveryRunId={}", properties.getMode(), runId);
        } catch (RuntimeException failure) {
            admission.close(stage + (taskId == null ? "" : ":task=" + taskId));
            String reason = failure instanceof TaskRecoveryInvariantException invariant
                    ? invariant.reasonCode() : failure.getClass().getSimpleName();
            log.error("TASK_EXECUTION_NOT_READY stage={} taskId={} recoveryRunId={} reason={}",
                    stage, taskId, runId, reason);
        }
    }
    @Override
    public Health health() {
        return admission.isReady() ? Health.up().build()
                : Health.down().withDetail("stage", admission.diagnostic()).build();
    }
}
