package com.agentflow.agent.task.dispatch;

import com.agentflow.agent.task.execution.TaskRunner;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class BoundedTaskDispatcher implements TaskDispatcher {
    private final ThreadPoolTaskExecutor executor;
    private final TaskRunner taskRunner;

    public BoundedTaskDispatcher(
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
            TaskRunner taskRunner
    ) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.taskRunner = Objects.requireNonNull(taskRunner, "taskRunner must not be null");
    }

    @Override
    public void dispatch(long taskId) {
        try {
            executor.execute(() -> taskRunner.run(taskId));
        } catch (RuntimeException ex) {
            throw new TaskDispatchRejectedException("Agent task executor rejected task " + taskId, ex);
        }
    }
}
