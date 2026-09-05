package com.agentflow.agent.task.dispatch;

import com.agentflow.agent.task.execution.TaskExecutionDelegate;
import com.agentflow.agent.task.execution.TaskExecutionOutcome;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(AgentTaskDispatcherProperties.class)
public class AgentTaskDispatcherConfiguration {

    @Bean(name = "agentTaskExecutor")
    public ThreadPoolTaskExecutor agentTaskExecutor(AgentTaskDispatcherProperties properties) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("agent-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentflow.task.execution",
            name = "mode",
            havingValue = "unavailable",
            matchIfMissing = false
    )
    public TaskExecutionDelegate unavailableTaskExecutionDelegate() {
        return request -> TaskExecutionOutcome.failed(
                "TASK_INTERNAL_ERROR",
                "Task execution delegate is not connected"
        );
    }
}
