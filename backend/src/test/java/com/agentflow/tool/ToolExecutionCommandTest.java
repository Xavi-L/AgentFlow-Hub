package com.agentflow.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class ToolExecutionCommandTest {

    @Test
    void shouldCreateStandaloneAndTaskScopedCommandsWithTheExpectedParentPair() {
        JsonNode arguments = JsonNodeFactory.instance.objectNode().put("orderNo", "order_1024");

        ToolExecutionCommand standalone = ToolExecutionCommand.standalone(11L, arguments);
        ToolExecutionCommand taskScoped = ToolExecutionCommand.taskScoped(11L, 21L, 31L, arguments);

        assertThat(standalone.toolId()).isEqualTo(11L);
        assertThat(standalone.taskId()).isNull();
        assertThat(standalone.stepId()).isNull();
        assertThat(standalone.arguments()).isSameAs(arguments);
        assertThat(taskScoped.toolId()).isEqualTo(11L);
        assertThat(taskScoped.taskId()).isEqualTo(21L);
        assertThat(taskScoped.stepId()).isEqualTo(31L);
        assertThat(taskScoped.arguments()).isSameAs(arguments);
    }

    @Test
    void shouldRejectMissingOrNonpositiveToolIds() {
        JsonNode arguments = JsonNodeFactory.instance.objectNode();

        assertThatNullPointerException()
                .isThrownBy(() -> ToolExecutionCommand.standalone(null, arguments))
                .withMessage("toolId must not be null");
        assertThatThrownBy(() -> ToolExecutionCommand.standalone(0L, arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolId must be positive");
        assertThatThrownBy(() -> ToolExecutionCommand.standalone(-1L, arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolId must be positive");
    }

    @Test
    void shouldRejectHalfBoundOrNonpositiveTaskStepPairs() {
        JsonNode arguments = JsonNodeFactory.instance.objectNode();

        assertThatThrownBy(() -> new ToolExecutionCommand(11L, 21L, null, arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskId and stepId must either both be null or both be present");
        assertThatThrownBy(() -> new ToolExecutionCommand(11L, null, 31L, arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskId and stepId must either both be null or both be present");
        assertThatThrownBy(() -> ToolExecutionCommand.taskScoped(11L, 0L, 31L, arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskId and stepId must be positive");
        assertThatThrownBy(() -> ToolExecutionCommand.taskScoped(11L, 21L, -1L, arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("taskId and stepId must be positive");
    }
}
