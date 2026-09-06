package com.agentflow.agent.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentflow.agent.task.dto.AgentTaskResponse;
import com.agentflow.agent.task.dto.CreateAgentTaskRequest;
import com.agentflow.agent.task.service.AgentTaskRestService;
import com.agentflow.agent.task.service.AgentTaskRestService.CreateTaskResponse;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.common.error.GlobalExceptionHandler;
import com.agentflow.common.web.TraceIdFilter;
import com.agentflow.user.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Focused HTTP binding/envelope tests; PostgreSQL lifecycle evidence is covered separately. */
class AgentTaskControllerTest {
    private final AgentTaskRestService service = mock(AgentTaskRestService.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldUseTheExplicitCreationOutcomeAndPreserveTheExactKeyAndInput() throws Exception {
        AuthenticatedUser owner = authenticate();
        CreateAgentTaskRequest request = new CreateAgentTaskRequest("  diagnose payment  ");
        // Equal payloads deliberately require the explicit flag to choose the HTTP status.
        when(service.create(owner, 301L, " request-key ", request))
                .thenReturn(new CreateTaskResponse(task("COMPLETED"), true),
                        new CreateTaskResponse(task("COMPLETED"), false));
        MockMvc mvc = mockMvc();

        mvc.perform(post("/api/v1/agents/301/tasks")
                        .header("Idempotency-Key", " request-key ")
                        .contentType("application/json")
                        .content("{\"userInput\":\"  diagnose payment  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.message").value("Task created"));
        mvc.perform(post("/api/v1/agents/301/tasks")
                        .header("Idempotency-Key", " request-key ")
                        .contentType("application/json")
                        .content("{\"userInput\":\"  diagnose payment  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value("9007199254740993"))
                .andExpect(jsonPath("$.message").value("Task reused"));
    }

    @Test
    void shouldRouteMissingIdempotencyKeyToTheStableServiceValidation() throws Exception {
        AuthenticatedUser owner = authenticate();
        when(service.create(eq(owner), eq(301L), isNull(), any(CreateAgentTaskRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.COMMON_PARAM_INVALID));

        mockMvc().perform(post("/api/v1/agents/301/tasks")
                        .contentType("application/json")
                        .content("{\"userInput\":\"diagnose payment\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));

        verify(service).create(owner, 301L, null, new CreateAgentTaskRequest("diagnose payment"));
    }

    @Test
    void shouldReturn409WhenTheServiceReportsAnIdempotencyConflict() throws Exception {
        AuthenticatedUser owner = authenticate();
        when(service.create(eq(owner), eq(301L), eq("same-key"), any(CreateAgentTaskRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.TASK_IDEMPOTENCY_CONFLICT));

        mockMvc().perform(post("/api/v1/agents/301/tasks")
                        .header("Idempotency-Key", "same-key")
                        .contentType("application/json")
                        .content("{\"userInput\":\"different input\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void shouldRejectUnknownDuplicateAndNonStringJsonFieldsBeforeServiceAccess() throws Exception {
        authenticate();
        MockMvc mvc = mockMvc();
        for (String body : List.of(
                "[]", "\"input\"", "{", "{\"userInput\":123}",
                "{\"userInput\":true}", "{\"userInput\":{}}", "{\"userInput\":[]}",
                "{\"userInput\":\"one\",\"userInput\":\"two\"}",
                "{\"userInput\":\"ok\"} {\"userId\":999}",
                "{\"userInput\":\"ok\",\"userId\":101}",
                "{\"userInput\":\"ok\",\"status\":\"COMPLETED\"}",
                "{\"userInput\":\"ok\",\"executionSnapshot\":{}}",
                "{\"userInput\":\"ok\",\"idempotencyKey\":\"body-key\"}")) {
            mvc.perform(post("/api/v1/agents/301/tasks")
                            .header("Idempotency-Key", "key")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_REQUEST_BODY_INVALID"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldRejectMissingNullAndBlankInputBeforeServiceAccess() throws Exception {
        authenticate();
        MockMvc mvc = mockMvc();
        for (String body : List.of("{}", "{\"userInput\":null}", "{\"userInput\":\"\"}",
                "{\"userInput\":\"  \\n \\t\"}")) {
            mvc.perform(post("/api/v1/agents/301/tasks")
                            .header("Idempotency-Key", "key")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldBindDefaultAndBoundedPaginationUsingTheJwtPrincipal() throws Exception {
        AuthenticatedUser owner = authenticate();
        when(service.list(eq(owner), any(PageRequest.class))).thenAnswer(invocation -> {
            PageRequest page = invocation.getArgument(1);
            return PageResult.of(List.of(), page.getPage(), page.getPageSize(), 0);
        });
        MockMvc mvc = mockMvc();

        mvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20));
        mvc.perform(get("/api/v1/tasks").param("page", "0").param("pageSize", "1000")
                        .param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(100));

        ArgumentCaptor<PageRequest> pages = ArgumentCaptor.forClass(PageRequest.class);
        verify(service, org.mockito.Mockito.times(2)).list(eq(owner), pages.capture());
        assertThat(pages.getAllValues()).extracting(PageRequest::getPageSize).containsExactly(20, 100);
    }

    @Test
    void shouldRejectMalformedAndOutOfIntegerRangePaginationBeforeServiceAccess() throws Exception {
        authenticate();
        MockMvc mvc = mockMvc();
        for (var request : List.of(get("/api/v1/tasks").param("page", "abc"),
                get("/api/v1/tasks").param("pageSize", "2147483648"))) {
            mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldDelegateDetailCancelAndTraceToTheJwtOwner() throws Exception {
        AuthenticatedUser owner = authenticate();
        when(service.get(owner, 401L)).thenReturn(task("COMPLETED"));
        when(service.cancel(owner, 401L)).thenReturn(task("RUNNING"));
        MockMvc mvc = mockMvc();

        mvc.perform(get("/api/v1/tasks/401").param("userId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.finalAnswer").value("Persisted answer"));
        mvc.perform(post("/api/v1/tasks/401/cancel").param("userId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        mvc.perform(get("/api/v1/tasks/401/trace").param("userId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));

        verify(service).get(owner, 401L);
        verify(service).cancel(owner, 401L);
        verify(service).trace(owner, 401L);
    }

    @Test
    void shouldKeepInvisibleResourcesOnTheUniformNotFoundContract() throws Exception {
        AuthenticatedUser owner = authenticate();
        BusinessException invisible = new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        when(service.create(eq(owner), eq(999L), eq("key"), any(CreateAgentTaskRequest.class)))
                .thenThrow(invisible);
        when(service.get(owner, 999L)).thenThrow(invisible);
        when(service.cancel(owner, 999L)).thenThrow(invisible);
        when(service.trace(owner, 999L)).thenThrow(invisible);
        MockMvc mvc = mockMvc();

        for (var request : List.of(
                post("/api/v1/agents/999/tasks").header("Idempotency-Key", "key")
                        .contentType("application/json").content("{\"userInput\":\"input\"}"),
                get("/api/v1/tasks/999"), post("/api/v1/tasks/999/cancel"),
                get("/api/v1/tasks/999/trace"))) {
            mvc.perform(request).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        }
    }

    @Test
    void shouldRejectNonNumericAndOutOfRangePathIdsBeforeServiceAccess() throws Exception {
        authenticate();
        MockMvc mvc = mockMvc();
        for (String id : List.of("not-a-number", "9223372036854775808")) {
            for (var request : List.of(
                    post("/api/v1/agents/" + id + "/tasks").header("Idempotency-Key", "key")
                            .contentType("application/json").content("{\"userInput\":\"input\"}"),
                    get("/api/v1/tasks/" + id), post("/api/v1/tasks/" + id + "/cancel"),
                    get("/api/v1/tasks/" + id + "/trace"))) {
                mvc.perform(request).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("COMMON_PARAM_INVALID"));
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void shouldNotPublishAnEventsRouteInV41() throws Exception {
        authenticate();
        mockMvc().perform(get("/api/v1/tasks/401/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(result -> assertThat(result.getHandler()).isNull());
        verifyNoInteractions(service);
    }

    private static AgentTaskResponse task(String status) {
        return new AgentTaskResponse("9007199254740993", "301", status, "GENERATING", null,
                "input", 6, 4, 8000, 1000, 2, 1, 60, 40, 100, "EXACT",
                "COMPLETED".equals(status) ? "Persisted answer" : null,
                JsonNodeFactory.instance.arrayNode(), null, null,
                null, null, null, 5, null, null);
    }

    private static AuthenticatedUser authenticate() {
        AuthenticatedUser owner = new AuthenticatedUser(101L, "xavier_01", "Xavier", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner, "test", List.of()));
        return owner;
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new AgentTaskController(service))
                .addPlaceholderValue("agentflow.api.prefix", "/api/v1")
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }
}
