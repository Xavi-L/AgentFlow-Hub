package com.agentflow.agent.task.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentflow.agent.task.sse.TaskSseService;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SSE negotiation must still permit JSON failures before a stream has been established. */
class AgentTaskSseControllerTest {
    private final TaskSseService streams = mock(TaskSseService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthenticatedUser owner = new AuthenticatedUser(101L, "owner", "Owner", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, "test", List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new AgentTaskSseController(streams))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnSafeJson500ForInvalidPersistedPayloadOrDatabaseFailureDespiteSseAccept() throws Exception {
        for (RuntimeException failure : List.of(
                new IllegalStateException("Persisted task payload is invalid JSON: private-payload"),
                new DataAccessResourceFailureException("Cannot read jdbc:postgresql://private-host/task"))) {
            doThrow(failure).when(streams).open(eq(101L), eq(401L), eq(0L),
                    any(HttpServletRequest.class), any(HttpServletResponse.class));

            mvc.perform(get("/api/v1/tasks/401/events").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("SYS_INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("Internal server error"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void shouldPreserveBusinessStatusAndJsonEnvelopeDespiteSseAccept() throws Exception {
        for (ErrorCode error : List.of(ErrorCode.COMMON_NOT_FOUND, ErrorCode.COMMON_PARAM_INVALID,
                ErrorCode.TASK_SSE_CAPACITY_EXCEEDED)) {
            doThrow(new BusinessException(error)).when(streams).open(eq(101L), eq(401L), eq(0L),
                    any(HttpServletRequest.class), any(HttpServletResponse.class));

            mvc.perform(get("/api/v1/tasks/401/events").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().is(error.getHttpStatus()))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(error.getCode()));
        }
    }

    @Test
    void shouldRejectMalformedOverflowAndNonPositiveTaskIdsAsJsonBeforeOpeningAStream() throws Exception {
        for (String taskId : List.of("not-a-number", "9223372036854775808", "0", "-1")) {
            mvc.perform(get("/api/v1/tasks/" + taskId + "/events").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }
        verifyNoInteractions(streams);
    }

    @Test
    void shouldRejectRepeatedQueryAndHeaderCursorsBeforeOpeningAStream() throws Exception {
        for (var request : List.of(
                get("/api/v1/tasks/401/events").param("afterSequence", "2", "2"),
                get("/api/v1/tasks/401/events").header("Last-Event-ID", "2", "2"),
                get("/api/v1/tasks/401/events").header("Last-Event-ID", "2,2"))) {
            mvc.perform(request.accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }
        verifyNoInteractions(streams);
    }

    @Test
    void shouldPassTheResolvedCursorAndAuthenticatedOwnerToTheStreamService() throws Exception {
        mvc.perform(get("/api/v1/tasks/401/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tasks/401/events").param("afterSequence", "3")
                        .param("userId", "999").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tasks/401/events").header("Last-Event-ID", "4")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tasks/401/events").param("afterSequence", "0005")
                        .header("Last-Event-ID", "5").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());

        for (long cursor : List.of(0L, 3L, 4L, 5L)) {
            verify(streams).open(eq(101L), eq(401L), eq(cursor),
                    any(HttpServletRequest.class), any(HttpServletResponse.class));
        }
    }
}
