package com.agentflow.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResultTest {

    @Test
    void shouldCalculateHasNextAndDefensivelyCopyItems() {
        List<String> source = new ArrayList<>(List.of("agent-a", "agent-b"));

        PageResult<String> result = PageResult.of(source, 2, 20, 45);
        source.add("late-change");

        assertThat(result.getItems()).containsExactly("agent-a", "agent-b");
        assertThat(result.isHasNext()).isTrue();
    }

    @Test
    void shouldRejectInvalidMetadata() {
        assertThatThrownBy(() -> PageResult.of(List.of(), 0, 20, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageResult.of(List.of(), 1, 101, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageResult.of(List.of(), 1, 20, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
