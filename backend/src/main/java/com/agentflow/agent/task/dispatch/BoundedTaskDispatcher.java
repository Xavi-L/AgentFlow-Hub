package com.agentflow.agent.task.dispatch;

import com.agentflow.agent.task.recovery.TaskExecutionAdmission;
import com.agentflow.agent.task.execution.TaskRunner;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class BoundedTaskDispatcher implements TaskDispatcher {
    private final TaskExecutionAdmission admission;
    private final ThreadPoolTaskExecutor executor;
    private final TaskRunner taskRunner;

    public BoundedTaskDispatcher(
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
            TaskRunner taskRunner,
            TaskExecutionAdmission admission
    ) {
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.taskRunner = Objects.requireNonNull(taskRunner, "taskRunner must not be null");
    }

    @Override
    public void dispatch(long taskId) {
        admission.requireReady();
        try {
            executor.execute(() -> taskRunner.run(taskId));
        } catch (RuntimeException ex) {
            throw new TaskDispatchRejectedException("Agent task executor rejected task " + taskId, ex);
        }
    }
}
