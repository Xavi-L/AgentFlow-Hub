package com.agentflow.agent.task.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AgentTaskDispatcherConfigurationTest {

    @Test
    void shouldFreezeTheV38DefaultPoolBoundsAndAbortPolicy() {
        AgentTaskDispatcherProperties properties = new AgentTaskDispatcherProperties();
        ThreadPoolTaskExecutor executor = new AgentTaskDispatcherConfiguration()
                .agentTaskExecutor(properties);
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldActuallyRejectWhenAZeroCapacityPoolIsSaturated() throws Exception {
        AgentTaskDispatcherProperties properties = new AgentTaskDispatcherProperties();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(0);
        ThreadPoolTaskExecutor executor = new AgentTaskDispatcherConfiguration()
                .agentTaskExecutor(properties);
        executor.initialize();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
