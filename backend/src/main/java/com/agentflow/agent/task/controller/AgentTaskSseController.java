package com.agentflow.agent.task.controller;

import com.agentflow.agent.task.sse.TaskSseCursor;
import com.agentflow.agent.task.sse.TaskSseService;
import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** The sole V42 public endpoint; JSON errors precede the event-stream response. */
@RestController
@RequestMapping("${agentflow.api.prefix}")
public class AgentTaskSseController {
    private static final Logger log = LoggerFactory.getLogger(AgentTaskSseController.class);
    private final TaskSseService streams;

    public AgentTaskSseController(TaskSseService streams) { this.streams = streams; }

    @GetMapping(value = "/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void events(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long taskId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (user == null || user.id() == null || user.id() <= 0) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        if (taskId == null || taskId <= 0) throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        String[] query = request.getParameterValues("afterSequence");
        long cursor = TaskSseCursor.resolve(query == null ? null : Arrays.asList(query),
                Collections.list(request.getHeaders("Last-Event-ID")));
        streams.open(user.id(), taskId, cursor, request, response);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> invalidId(MethodArgumentTypeMismatchException ex) {
        return business(new BusinessException(ErrorCode.COMMON_PARAM_INVALID));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        log.error("Cannot establish task event stream", ex);
        return business(new BusinessException(ErrorCode.SYS_INTERNAL_ERROR));
    }
}
