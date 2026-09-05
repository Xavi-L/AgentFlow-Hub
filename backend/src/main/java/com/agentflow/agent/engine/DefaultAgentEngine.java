package com.agentflow.agent.engine;

import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.infra.llm.LlmChatRequest;
import com.agentflow.infra.llm.LlmChatResult;
import com.agentflow.infra.llm.LlmGateway;
import com.agentflow.infra.llm.LlmMessage;
import com.agentflow.tool.ToolDefinition;
import com.agentflow.tool.ToolDefinitionService;
import com.agentflow.tool.ToolExecutionCommand;
import com.agentflow.tool.ToolExecutionResult;
import com.agentflow.tool.ToolRuntime;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** V36's synchronous, non-persistent JSON-decision execution loop. */
@Service
public final class DefaultAgentEngine implements AgentEngine {
    private final AgentAppMapper agentAppMapper;
    private final ToolDefinitionService toolDefinitionService;
    private final LlmGateway llmGateway;
    private final ToolRuntime toolRuntime;
    private final AgentPromptBuilder promptBuilder;
    private final AgentDecisionParser decisionParser;
    private final Clock clock;
    private TaskSnapshotAgentExecutor taskExecutor;

    public DefaultAgentEngine(
            AgentAppMapper agentAppMapper,
            ToolDefinitionService toolDefinitionService,
            LlmGateway llmGateway,
            ToolRuntime toolRuntime,
            AgentPromptBuilder promptBuilder,
            AgentDecisionParser decisionParser,
            Clock clock
    ) {
        this.agentAppMapper = Objects.requireNonNull(agentAppMapper, "agentAppMapper must not be null");
        this.toolDefinitionService = Objects.requireNonNull(
                toolDefinitionService,
                "toolDefinitionService must not be null"
        );
        this.llmGateway = Objects.requireNonNull(llmGateway, "llmGateway must not be null");
        this.toolRuntime = Objects.requireNonNull(toolRuntime, "toolRuntime must not be null");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
        this.decisionParser = Objects.requireNonNull(decisionParser, "decisionParser must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultAgentEngine(
            AgentAppMapper agentAppMapper,
            ToolDefinitionService toolDefinitionService,
            LlmGateway llmGateway,
            ToolRuntime toolRuntime,
            AgentPromptBuilder promptBuilder,
            AgentDecisionParser decisionParser,
            Clock clock,
            TaskSnapshotAgentExecutor taskExecutor
    ) {
        this(agentAppMapper, toolDefinitionService, llmGateway, toolRuntime,
                promptBuilder, decisionParser, clock);
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor must not be null");
    }

    @Override
    public com.agentflow.agent.task.execution.TaskExecutionOutcome execute(
            com.agentflow.agent.task.execution.TaskExecutionRequest request
    ) {
        return Objects.requireNonNull(taskExecutor, "Task snapshot executor is not configured").execute(request);
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionCommand command) {
        validateCommand(command);
        Instant startedAt = clock.instant();

        AgentApp persisted = agentAppMapper.selectVisibleOwnedById(command.agentId(), command.userId());
        if (persisted == null) {
            throw failure(AgentFailureType.AGENT_NOT_FOUND, "Agent is unavailable");
        }
        AgentExecutionConfigSnapshot snapshot = snapshot(persisted);
        if (!snapshot.active()) {
            throw failure(AgentFailureType.AGENT_DISABLED, "Agent is disabled");
        }

        BudgetGuard budget = new BudgetGuard(snapshot, clock, startedAt);
        budget.checkBoundary();
        List<AgentToolSpec> safeTools = loadSafeTools();
        budget.checkBoundary();
        AgentExecutionContext context = new AgentExecutionContext(
                command,
                snapshot,
                safeTools,
                budget
        );

        while (true) {
            int thinkingOutputCap = budget.beginDecisionCall();
            LlmChatResult thinking = invokeLlm(
                    context,
                    promptBuilder.buildThinkingMessages(context),
                    thinkingOutputCap
            );
            AgentDecision decision = decisionParser.parse(thinking.content(), safeTools);

            if (decision instanceof ToolCallDecision toolCall) {
                context.appendObservation(invokeTool(context, toolCall));
                continue;
            }

            FinalAnswerDecision finalDecision = (FinalAnswerDecision) decision;
            int finalOutputCap = budget.beginFinalAnswerCall();
            LlmChatResult finalAnswer = invokeLlm(
                    context,
                    promptBuilder.buildFinalAnswerMessages(context, finalDecision.answerPlan()),
                    finalOutputCap
            );
            return new AgentExecutionResult(
                    finalAnswer.content(),
                    budget.stepsUsed(),
                    budget.toolCallsUsed(),
                    budget.inputTokens(),
                    budget.outputTokens(),
                    budget.totalTokens()
            );
        }
    }

    private LlmChatResult invokeLlm(
            AgentExecutionContext context,
            List<LlmMessage> messages,
            int maxOutputTokens
    ) {
        AgentExecutionConfigSnapshot snapshot = context.configSnapshot();
        LlmChatResult result;
        try {
            result = llmGateway.chat(new LlmChatRequest(
                    snapshot.modelProvider(),
                    snapshot.modelName(),
                    messages,
                    snapshot.temperature(),
                    snapshot.topP(),
                    maxOutputTokens
            ));
        } catch (RuntimeException ignored) {
            context.budgetGuard().checkDeadline();
            throw failure(AgentFailureType.LLM_FAILURE, "LLM call failed");
        }
        if (result == null) {
            context.budgetGuard().checkDeadline();
            throw failure(AgentFailureType.LLM_FAILURE, "LLM call failed");
        }
        context.budgetGuard().completeLlmCall(result.usage());
        return result;
    }

    private AgentObservation invokeTool(
            AgentExecutionContext context,
            ToolCallDecision decision
    ) {
        BudgetGuard budget = context.budgetGuard();
        budget.beginToolCall();
        ToolExecutionResult result;
        try {
            result = toolRuntime.execute(ToolExecutionCommand.standalone(
                    decision.toolId(),
                    decision.arguments()
            ));
        } catch (RuntimeException ignored) {
            budget.checkDeadline();
            throw failure(AgentFailureType.TOOL_FAILURE, "Tool execution failed");
        }
        budget.completeToolCall();
        if (result == null
                || !result.success()
                || !decision.toolCode().equals(result.toolCode())
                || result.summary() == null
                || result.summary().isBlank()
                || result.data() == null) {
            throw failure(AgentFailureType.TOOL_FAILURE, "Tool execution failed");
        }
        try {
            return new AgentObservation(result.toolCode(), result.summary(), result.data());
        } catch (RuntimeException ignored) {
            throw failure(AgentFailureType.TOOL_FAILURE, "Tool execution failed");
        }
    }

    private List<AgentToolSpec> loadSafeTools() {
        try {
            List<ToolDefinition> definitions = toolDefinitionService.listActive();
            Set<Long> ids = new HashSet<>();
            Set<String> codes = new HashSet<>();
            return definitions.stream()
                    .filter(definition -> "BUILTIN".equals(definition.type()))
                    .map(definition -> {
                        if (!"ACTIVE".equals(definition.status())
                                || !ids.add(definition.id())
                                || !codes.add(definition.toolCode())) {
                            throw new IllegalStateException("Invalid active tool registry");
                        }
                        return new AgentToolSpec(
                                definition.id(),
                                definition.toolCode(),
                                definition.name(),
                                definition.description(),
                                definition.inputSchema()
                        );
                    }).toList();
        } catch (RuntimeException ignored) {
            throw failure(AgentFailureType.TOOL_FAILURE, "Tool registry is unavailable");
        }
    }

    private static AgentExecutionConfigSnapshot snapshot(AgentApp persisted) {
        try {
            return AgentExecutionConfigSnapshot.from(persisted);
        } catch (RuntimeException ignored) {
            throw failure(
                    AgentFailureType.INVALID_AGENT_CONFIG,
                    "Agent execution configuration is invalid"
            );
        }
    }

    private static void validateCommand(AgentExecutionCommand command) {
        if (command == null
                || command.userId() == null || command.userId() <= 0
                || command.agentId() == null || command.agentId() <= 0
                || command.userInput() == null || command.userInput().isBlank()) {
            throw failure(AgentFailureType.INVALID_COMMAND, "Agent execution command is invalid");
        }
    }

    private static AgentExecutionException failure(AgentFailureType type, String message) {
        return new AgentExecutionException(type, message);
    }
}
