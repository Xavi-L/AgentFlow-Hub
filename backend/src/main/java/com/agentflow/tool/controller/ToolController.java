package com.agentflow.tool.controller;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.tool.ToolDefinitionService;
import com.agentflow.tool.ToolExecutionCommand;
import com.agentflow.tool.ToolExecutionResult;
import com.agentflow.tool.ToolRuntime;
import com.agentflow.tool.dto.ToolDefinitionResponse;
import com.agentflow.tool.dto.ToolTestRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, read-only discovery and standalone ToolRuntime acceptance endpoints. */
@RestController
@RequestMapping("${agentflow.api.prefix}/tools")
public class ToolController {
    private final ToolDefinitionService toolDefinitionService;
    private final ToolRuntime toolRuntime;

    public ToolController(ToolDefinitionService toolDefinitionService, ToolRuntime toolRuntime) {
        this.toolDefinitionService = Objects.requireNonNull(
                toolDefinitionService,
                "toolDefinitionService must not be null"
        );
        this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime must not be null");
    }

    @GetMapping
    public ApiResponse<List<ToolDefinitionResponse>> listTools() {
        return ApiResponse.success(
                "Tools retrieved",
                toolDefinitionService.listActive().stream().map(ToolDefinitionResponse::from).toList()
        );
    }

    @PostMapping("/{toolId}/test")
    public ApiResponse<ToolExecutionResult> testTool(
            @PathVariable Long toolId,
            @RequestBody ToolTestRequest request
    ) {
        if (request == null) {
            throw new BusinessException(ErrorCode.COMMON_REQUEST_BODY_INVALID);
        }
        ToolExecutionResult result = toolRuntime.execute(
                ToolExecutionCommand.standalone(toolId, request.arguments())
        );
        return ApiResponse.success("Tool executed", result);
    }
}
