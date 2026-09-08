package com.agentflow.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TaskExecutionPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TaskExecutionProperties.class);

    @Test
    void defaultDecisionOutputCapRemains512() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TaskExecutionProperties.class);
            assertThat(context.getBean(TaskExecutionProperties.class).getDecisionMaxOutputTokens()).isEqualTo(512);
            assertThat(context.getBean(TaskExecutionProperties.class).isDecisionJsonSchemaEnabled()).isFalse();
            assertThat(context.getBean(TaskExecutionProperties.class).isDecisionJsonObjectEnabled()).isFalse();
            assertThat(context.getBean(TaskExecutionProperties.class).isProviderThinkingDisabled()).isFalse();
            assertThat(context.getBean(TaskExecutionProperties.class).getFinalMaxOutputTokens()).isNull();
        });
    }

    @Test
    void bindsOptInDecisionJsonSchemaWithoutChangingTheDefaultOutputCap() {
        contextRunner.withPropertyValues("agentflow.task.execution.decision-json-schema-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TaskExecutionProperties properties = context.getBean(TaskExecutionProperties.class);
                    assertThat(properties.isDecisionJsonSchemaEnabled()).isTrue();
                    assertThat(properties.getDecisionMaxOutputTokens()).isEqualTo(512);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8192, 16384})
    void bindsExplicitDecisionCapIncludingBothAllowedBoundaries(int cap) {
        contextRunner.withPropertyValues("agentflow.task.execution.decision-max-output-tokens=" + cap)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TaskExecutionProperties.class).getDecisionMaxOutputTokens()).isEqualTo(cap);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16385})
    void rejectsDecisionCapOutsideServerBoundsAtStartup(int cap) {
        contextRunner.withPropertyValues("agentflow.task.execution.decision-max-output-tokens=" + cap)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void bindsExplicitProviderModesAndRejectsConflictingDecisionFormats() {
        contextRunner.withPropertyValues("agentflow.task.execution.decision-json-object-enabled=true",
                        "agentflow.task.execution.provider-thinking-disabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TaskExecutionProperties properties = context.getBean(TaskExecutionProperties.class);
                    assertThat(properties.isDecisionJsonObjectEnabled()).isTrue();
                    assertThat(properties.isProviderThinkingDisabled()).isTrue();
                    assertThat(properties.isDecisionJsonSchemaEnabled()).isFalse();
                });
        contextRunner.withPropertyValues("agentflow.task.execution.decision-json-object-enabled=true",
                        "agentflow.task.execution.decision-json-schema-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8192, 16384})
    void bindsOptionalFinalOutputCapWithinServerBounds(int cap) {
        contextRunner.withPropertyValues("agentflow.task.execution.final-max-output-tokens=" + cap)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TaskExecutionProperties.class).getFinalMaxOutputTokens()).isEqualTo(cap);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 16385})
    void rejectsOptionalFinalOutputCapOutsideServerBounds(int cap) {
        contextRunner.withPropertyValues("agentflow.task.execution.final-max-output-tokens=" + cap)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                });
    }
}
