package com.agentflow.agent.task.recovery;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TaskRecoveryProperties.class)
public class TaskRecoveryConfiguration {
    @Bean(destroyMethod = "")
    public TaskExecutionProcessLock taskExecutionProcessLock(TaskRecoveryProperties properties) {
        return TaskExecutionProcessLock.acquire(properties);
    }
    /** Acquire the process lock before Flyway can perform any schema writes. */
    @Bean
    public FlywayMigrationStrategy taskRecoveryMigrationStrategy(TaskExecutionProcessLock lock, TaskExecutionAdmission admission) {
        return flyway -> {
            lock.requireHeld();
            try { flyway.migrate(); }
            catch (RuntimeException failure) {
                admission.close("MIGRATION_FAILED");
                LoggerFactory.getLogger(TaskRecoveryConfiguration.class).error(
                        "TASK_EXECUTION_NOT_READY stage=MIGRATION failureType={}", failure.getClass().getSimpleName());
                throw failure;
            }
        };
    }
}
