package com.agentflow.agent.engine;

import com.agentflow.agent.rag.SnapshotRagResult;
import com.agentflow.agent.rag.SnapshotRagService;
import com.agentflow.agent.snapshot.AgentTaskExecutionSnapshot;
import com.agentflow.agent.settings.ResolvedAgentExecutionSettings;
import com.agentflow.agent.task.execution.TaskExecutionOutcome;
import com.agentflow.agent.task.execution.TaskExecutionRequest;
import com.agentflow.agent.task.execution.TaskTokenUsage;
import com.agentflow.agent.task.model.TaskEventType;
import com.agentflow.agent.task.model.TaskPhase;
import com.agentflow.agent.task.model.TaskTerminationReason;
import com.agentflow.agent.task.model.TokenUsageQuality;
import com.agentflow.agent.task.service.AgentTaskLifecycleTransactionService;
import com.agentflow.agent.trace.*;
import com.agentflow.infra.llm.*;
import com.agentflow.tool.ToolExecutionCommand;
import com.agentflow.tool.ToolExecutionResult;
import com.agentflow.tool.ToolRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Real task execution loop. The Runner alone owns task terminal transitions and answer publication. */
@Component
public final class TaskSnapshotAgentExecutor {
    private static final Pattern CITATION = Pattern.compile("\\[(S\\d+)]");
    private static final Pattern CITATION_LIKE = Pattern.compile("\\[+[SC][^\\]]*(?:]+|$)");
    private final SnapshotRagService ragService;
    private final LlmGateway gateway;
    private final ToolRuntime tools;
    private final ExecutionRecorderFactory recorderFactory;
    private final AgentTaskLifecycleTransactionService lifecycle;
    private final AgentDecisionParser parser;
    private final TaskPromptBuilder prompts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int decisionMaxOutputTokens;
    private final boolean decisionJsonSchemaEnabled;
    private final String decisionResponseFormat;
    private final String thinkingMode;
    private final Integer finalMaxOutputTokens;

    public TaskSnapshotAgentExecutor(SnapshotRagService ragService, LlmGateway gateway, ToolRuntime tools,
            ExecutionRecorderFactory recorderFactory, AgentTaskLifecycleTransactionService lifecycle,
            AgentDecisionParser parser, TaskPromptBuilder prompts, ObjectMapper mapper, Clock clock,
            TaskExecutionProperties executionProperties) {
        this.ragService = ragService;
        this.gateway = gateway;
        this.tools = tools;
        this.recorderFactory = recorderFactory;
        this.lifecycle = lifecycle;
        this.parser = parser;
        this.prompts = prompts;
        this.mapper = mapper;
        this.clock = clock;
        this.decisionMaxOutputTokens = executionProperties.getDecisionMaxOutputTokens();
        this.decisionJsonSchemaEnabled = executionProperties.isDecisionJsonSchemaEnabled();
        if (!executionProperties.isDecisionFormatExclusive()) {
            throw new IllegalArgumentException("decision JSON schema and JSON object modes are mutually exclusive");
        }
        this.decisionResponseFormat = executionProperties.isDecisionJsonObjectEnabled() ? "json_object" : null;
        this.thinkingMode = executionProperties.isProviderThinkingDisabled() ? "disabled" : null;
        this.finalMaxOutputTokens = executionProperties.getFinalMaxOutputTokens();
    }

    public TaskExecutionOutcome execute(TaskExecutionRequest request) {
        State state = new State(request);
        try {
            validate(request);
            state.decisionResponseSchema = (state.settings == null ? decisionJsonSchemaEnabled
                    : "JSON_SCHEMA".equals(state.settings.decisionResponseFormat()))
                    ? AgentDecisionResponseSchema.fromTools(mapper, request.executionSnapshot().tools()) : null;
            state.recorder = recorderFactory.open(request.taskId());
            state.boundary();
            SnapshotRagResult rag = retrieve(state);
            List<AgentToolSpec> allowedTools = request.executionSnapshot().tools().stream()
                    .map(tool -> new AgentToolSpec(Long.parseLong(tool.toolId()), tool.toolCode(),
                            tool.name(), tool.description(), tool.inputSchema())).toList();
            String plan = "Answer from the available evidence and state any limitations";
            TaskTerminationReason reason = TaskTerminationReason.ANSWERED;
            while (true) {
                state.boundary();
                if (state.turns >= request.executionSnapshot().agent().maxDecisionTurns()) {
                    reason = TaskTerminationReason.MAX_DECISION_TURNS;
                    break;
                }
                if (state.toolCalls >= request.executionSnapshot().agent().maxToolCalls()) {
                    reason = TaskTerminationReason.MAX_TOOL_CALLS;
                    break;
                }
                List<LlmMessage> messages = prompts.decision(request, rag, state.observations,
                        request.executionSnapshot().agent().maxDecisionTurns() - state.turns,
                        request.executionSnapshot().agent().maxToolCalls() - state.toolCalls);
                int finalInput = TaskTokenEstimator.inputTokens(
                        prompts.finalAnswer(request, rag, state.observations, plan));
                int cap = outputCap(state, messages, finalInput + request.finalTokenReserve(),
                        state.settings == null ? decisionMaxOutputTokens : state.settings.decisionMaxOutputTokens(),
                        state.decisionResponseSchema);
                phase(state, TaskPhase.DECIDING);
                AgentDecision decision = callLlm(state, messages, cap, LlmCallType.DECISION,
                        text -> parser.parse(text, allowedTools));
                if (decision instanceof FinalAnswerDecision finish) {
                    plan = finish.answerPlan();
                    break;
                }
                invokeTool(state, (ToolCallDecision) decision);
            }
            if (reason != TaskTerminationReason.ANSWERED) {
                state.observations.add(mapper.createObjectNode().put("type", "BUDGET_LIMIT")
                        .put("reason", reason.name()).put("readOnly", true));
                plan = "Give a limited answer from existing facts; execution stopped because " + reason.name();
            }
            List<LlmMessage> messages = prompts.finalAnswer(request, rag, state.observations, plan);
            // Optional extra output uses remaining budget; only the persisted reserve is guaranteed.
            int desiredFinalCap = state.settings != null ? state.settings.finalMaxOutputTokens()
                    : finalMaxOutputTokens == null ? request.finalTokenReserve()
                    : Math.max(request.finalTokenReserve(), finalMaxOutputTokens);
            int cap = outputCap(state, messages, 0, desiredFinalCap, null);
            // A final call must retain the entire frozen output reserve, not a silently reduced cap.
            if (cap < request.finalTokenReserve()) throw tokenExhausted();
            phase(state, TaskPhase.GENERATING);
            state.recorder.appendEvent(event(TaskEventType.FINAL_GENERATION_STARTED,
                    mapper.createObjectNode().put("maxOutputTokens", cap)));
            String answer = callLlm(state, messages, cap, LlmCallType.FINAL_GENERATION, text -> {
                validateCitations(text, rag);
                return text;
            });
            return TaskExecutionOutcome.completed(answer, reason, state.turns, state.toolCalls,
                    state.usage(), citations(answer, rag));
        } catch (TaskExecutionAbort abort) {
            return failure(state, abort);
        } catch (AgentExecutionException invalid) {
            return failure(state, new TaskExecutionAbort("AGENT_INVALID_DECISION", "Model decision is invalid"));
        } catch (RuntimeException ignored) {
            return failure(state, new TaskExecutionAbort("AGENT_EXECUTION_FAILED", "Task execution failed"));
        }
    }

    private SnapshotRagResult retrieve(State state) {
        phase(state, TaskPhase.RETRIEVING);
        StepHandle step = startStep(state, StepType.PRE_RETRIEVAL, "Snapshot-scoped knowledge retrieval");
        long started = System.nanoTime();
        SnapshotRagResult result;
        try {
            result = TaskExternalCallDeadline.call(
                    () -> ragService.retrieve(state.request, state::boundary), state.request.deadlineAt(), clock, state::boundary);
            state.boundary();
        } catch (RuntimeException ex) {
            TaskExecutionAbort abort = asAbort(ex, "RAG_RETRIEVAL_FAILED", "Knowledge retrieval failed");
            state.recorder.recordRagRetrieval(ragRecord(state, step, null, elapsed(started), abort));
            failStep(state, step, abort);
            throw abort;
        }
        state.recorder.recordRagRetrieval(ragRecord(state, step, result, elapsed(started), null));
        ObjectNode summary = mapper.createObjectNode().put("validHitCount", result.hits().size())
                .put("candidateCount", result.candidateCount()).put("staleHitCount", result.staleHitCount());
        state.recorder.completeStep(step, new StepSummary(summary));
        state.activeStep = null;
        state.recorder.appendEvent(event(TaskEventType.RAG_FINISHED, summary.put("stepId", step.stepId())));
        return result;
    }

    private RagRetrievalRecord ragRecord(State state, StepHandle step, SnapshotRagResult result,
            long latency, TaskExecutionAbort error) {
        var retrieval = state.request.executionSnapshot().retrieval();
        String profile = result == null ? retrieval.knowledgeBases().stream()
                .map(AgentTaskExecutionSnapshot.KnowledgeBaseSnapshot::embeddingProfileCode)
                .findFirst().orElse("NONE") : result.embeddingProfileCode();
        return new RagRetrievalRecord(step, state.request.userInput(), profile,
                mapper.valueToTree(retrieval), retrieval.topK(), retrieval.similarityThreshold(),
                result == null ? 0 : result.candidateCount(), result == null ? 0 : result.hits().size(),
                result == null ? 0 : result.staleHitCount(), latency,
                error == null ? TraceRecordStatus.SUCCESS : TraceRecordStatus.FAILED,
                error == null ? null : error.code(), error == null ? null : error.getMessage(),
                result == null ? List.of() : result.hits());
    }

    private <T> T callLlm(State state, List<LlmMessage> messages, int cap,
            LlmCallType type, Function<String, T> validation) {
        state.boundary();
        StepHandle step = startStep(state, type.requiredStepType(),
                type == LlmCallType.DECISION ? "Model decision" : "Final answer generation");
        var model = state.request.executionSnapshot().chatModel();
        LlmResponseSchema responseSchema = type == LlmCallType.DECISION ? state.decisionResponseSchema : null;
        String format = state.settings == null ? decisionResponseFormat
                : "JSON_OBJECT".equals(state.settings.decisionResponseFormat()) ? "json_object" : null;
        String thinking = state.settings == null ? thinkingMode
                : "DISABLED".equals(state.settings.thinkingMode()) ? "disabled" : null;
        Integer timeout = state.settings == null ? null : state.settings.modelCallTimeoutSeconds();
        LlmChatRequest request = new LlmChatRequest(model.provider(), model.model(), messages,
                model.temperature(), model.topP(), cap, responseSchema,
                type == LlmCallType.DECISION ? format : null, thinking, timeout);
        JsonNode requestJson = mapper.valueToTree(request);
        long started = System.nanoTime();
        LlmChatResult result = null;
        TaskTokenUsage callUsage = null;
        T validated;
        java.util.concurrent.atomic.AtomicBoolean callStarted = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            state.boundary();
            Instant callDeadline = state.request.deadlineAt();
            boolean callLimitFirst = timeout != null && clock.instant().plusSeconds(timeout).isBefore(callDeadline);
            if (callLimitFirst) callDeadline = clock.instant().plusSeconds(timeout);
            result = TaskExternalCallDeadline.call(() -> {
                state.boundary();
                if (type == LlmCallType.DECISION) state.turns++;
                callStarted.set(true);
                return gateway.chat(request);
            }, callDeadline, clock, state::boundary,
                    callLimitFirst ? "AGENT_LLM_TIMEOUT" : "TASK_TIMED_OUT",
                    callLimitFirst ? "Model call exceeded its time limit" : "Task deadline was exceeded");
            if (result == null) throw new TaskExecutionAbort("AGENT_LLM_FAILED", "Model call returned no result");
            callUsage = measuredUsage(result, messages, responseSchema);
            state.account(callUsage);
            state.boundary();
            if (state.usage().totalTokens() > state.request.executionSnapshot().agent().maxTotalTokens()) {
                throw tokenExhausted();
            }
            if ("length".equalsIgnoreCase(result.finishReason())) {
                throw new TaskExecutionAbort("AGENT_LLM_OUTPUT_LIMIT",
                        "Model reached its output limit before completing a response");
            }
            validated = validation.apply(result.content());
            if (result.content().getBytes(StandardCharsets.UTF_8).length > 192 * 1024) {
                throw new TaskExecutionAbort("AGENT_RESPONSE_LIMIT", "Model response exceeds the bounded output size");
            }
        } catch (RuntimeException ex) {
            LlmFailureMetadata failureMetadata = ex instanceof LlmGatewayException failure ? failure.metadata() : null;
            if (!callStarted.get()) {
                TaskExecutionAbort abort = asAbort(ex, "AGENT_LLM_FAILED", "Model call failed");
                failStep(state, step, abort);
                throw abort;
            }
            if (callUsage == null) {
                callUsage = failureMetadata == null ? null : knownUsage(failureMetadata.usage());
                // A provider failure may still have consumed tokens; estimate only when no usable usage returned.
                if (callUsage == null) callUsage = new TaskTokenUsage(
                        estimatedInputTokens(messages, responseSchema), cap, TokenUsageQuality.ESTIMATED);
                state.account(callUsage);
            }
            TaskExecutionAbort abort = asAbort(ex, "AGENT_LLM_FAILED", "Model call failed");
            // Failed decision records deliberately have no raw response body: malformed JSON
            // cannot trigger the Trace JSON sanitizer and replace the original safe failure.
            state.recorder.recordLlmCall(llmRecord(step, type, request, requestJson, result,
                    callUsage, elapsed(started), null, abort, failureMetadata));
            failStep(state, step, abort);
            throw abort;
        }
        String traceResponse = type == LlmCallType.DECISION ? safeDecision((AgentDecision) validated) : result.content();
        state.recorder.recordLlmCall(llmRecord(step, type, request, requestJson, result,
                callUsage, elapsed(started), traceResponse, null));
        ObjectNode summary = mapper.createObjectNode().put("totalTokens", callUsage.totalTokens())
                .put("usageQuality", callUsage.quality().name());
        if (type == LlmCallType.DECISION) {
            summary.put("decisionType", validated instanceof ToolCallDecision ? "CALL_TOOL" : "FINISH");
        }
        state.recorder.completeStep(step, new StepSummary(summary));
        state.activeStep = null;
        if (type == LlmCallType.DECISION) {
            state.recorder.appendEvent(event(TaskEventType.DECISION_FINISHED, summary.put("stepId", step.stepId())));
        }
        return validated;
    }

    private LlmCallRecord llmRecord(StepHandle step, LlmCallType type, LlmChatRequest request,
            JsonNode requestJson, LlmChatResult result, TaskTokenUsage usage,
            long latency, String response, TaskExecutionAbort error) {
        return llmRecord(step, type, request, requestJson, result, usage, latency, response, error, null);
    }

    private LlmCallRecord llmRecord(StepHandle step, LlmCallType type, LlmChatRequest request,
            JsonNode requestJson, LlmChatResult result, TaskTokenUsage usage,
            long latency, String response, TaskExecutionAbort error, LlmFailureMetadata metadata) {
        return new LlmCallRecord(step, type, request.modelProvider(), request.modelName(),
                optional(result != null ? result.resolvedModel() : metadata == null ? null : metadata.resolvedModel(), 128),
                requestJson, response,
                optional(result != null ? result.finishReason() : metadata == null ? null : metadata.finishReason(), 64),
                optional(result != null ? result.providerRequestId() : metadata == null ? null : metadata.providerRequestId(), 255),
                usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), usage.quality(),
                result != null ? result.latencyMs() : metadata == null ? latency : metadata.latencyMs(),
                error == null ? TraceRecordStatus.SUCCESS : TraceRecordStatus.FAILED,
                error == null ? null : error.code(), error == null ? null : error.getMessage());
    }

    private void invokeTool(State state, ToolCallDecision decision) {
        String key = duplicateKey(decision);
        int occurrences = state.duplicateCounts.merge(key, 1, Integer::sum);
        if (occurrences >= 3) {
            throw new TaskExecutionAbort("AGENT_DUPLICATE_TOOL_LOOP", "Repeated tool intent forms a loop");
        }
        phase(state, TaskPhase.EXECUTING_TOOL);
        StepHandle step = startStep(state, StepType.TOOL_CALL,
                occurrences == 2 ? "Reuse existing tool observation" : "Execute snapshot tool");
        ObjectNode eventData = mapper.createObjectNode().put("stepId", step.stepId())
                .put("toolCode", decision.toolCode()).put("reused", occurrences == 2);
        state.recorder.appendEvent(event(TaskEventType.TOOL_STARTED, eventData));
        try {
            state.boundary();
            if (occurrences == 2) {
                tools.validateTaskSnapshot(ToolExecutionCommand.taskScoped(
                        decision.toolId(), state.request.taskId(), step.stepId(), state.request.userId(),
                        state.request.agentId(), state.request.executionSnapshot(), decision.arguments(),
                        state.request.deadlineAt(), state::boundary));
                ObjectNode reused = state.cachedObservations.get(key).deepCopy();
                reused.put("reused", true);
                state.observations.add(reused);
            } else {
                state.toolCalls++;
                ToolExecutionResult result = tools.execute(ToolExecutionCommand.taskScoped(
                        decision.toolId(), state.request.taskId(), step.stepId(), state.request.userId(),
                        state.request.agentId(), state.request.executionSnapshot(), decision.arguments(),
                        state.request.deadlineAt(), state::boundary));
                state.boundary();
                if (result == null || !result.success() || !decision.toolCode().equals(result.toolCode())
                        || result.summary() == null || result.summary().isBlank() || result.data() == null) {
                    throw new TaskExecutionAbort("AGENT_TOOL_FAILED", "Tool execution failed");
                }
                ObjectNode observation = prompts.observation(decision.toolCode(), result.summary(), result.data(), false);
                state.observations.add(observation);
                state.cachedObservations.put(key, observation);
            }
            state.boundary();
        } catch (RuntimeException ex) {
            TaskExecutionAbort abort = asAbort(ex, "AGENT_TOOL_FAILED", "Tool execution failed");
            failStep(state, step, abort);
            state.recorder.appendEvent(event(TaskEventType.TOOL_FINISHED,
                    eventData.put("status", "FAILED").put("errorCode", abort.code())));
            throw abort;
        }
        state.recorder.completeStep(step, new StepSummary(eventData));
        state.activeStep = null;
        state.recorder.appendEvent(event(TaskEventType.TOOL_FINISHED, eventData.put("status", "SUCCESS")));
    }

    private int outputCap(State state, List<LlmMessage> messages, int reserved, int desired,
            LlmResponseSchema responseSchema) {
        state.boundary();
        int input = estimatedInputTokens(messages, responseSchema);
        long available = (long) state.request.executionSnapshot().agent().maxTotalTokens()
                - state.usage().totalTokens() - input - reserved;
        long contextAvailable = (long) state.request.executionSnapshot().chatModel().contextWindow() - input;
        int result = (int) Math.min(desired, Math.min(available, contextAvailable));
        if (result < 1) throw tokenExhausted();
        return result;
    }

    private int estimatedInputTokens(List<LlmMessage> messages, LlmResponseSchema responseSchema) {
        // Some providers include the schema in model input; reserve/account it conservatively.
        return Math.addExact(TaskTokenEstimator.inputTokens(messages), responseSchema == null ? 0
                : TaskTokenEstimator.textTokens(mapper.valueToTree(responseSchema).toString()));
    }

    private TaskTokenUsage measuredUsage(LlmChatResult result, List<LlmMessage> messages,
            LlmResponseSchema responseSchema) {
        TaskTokenUsage known = knownUsage(result.usage());
        if (known != null) return known;
        return new TaskTokenUsage(estimatedInputTokens(messages, responseSchema),
                TaskTokenEstimator.textTokens(result.content()), TokenUsageQuality.ESTIMATED);
    }

    private TaskTokenUsage knownUsage(LlmTokenUsage usage) {
        if (usage != null && usage.known()) {
            if ((long) usage.inputTokens() + usage.outputTokens() != usage.totalTokens()) {
                int total = Math.max(usage.totalTokens(), Math.addExact(usage.inputTokens(), usage.outputTokens()));
                return new TaskTokenUsage(usage.inputTokens(), total - usage.inputTokens(), TokenUsageQuality.ESTIMATED);
            }
            return new TaskTokenUsage(usage.inputTokens(), usage.outputTokens(), TokenUsageQuality.EXACT);
        }
        return null;
    }

    private String safeDecision(AgentDecision decision) {
        ObjectNode value = mapper.createObjectNode();
        if (decision instanceof ToolCallDecision call) {
            value.put("type", "CALL_TOOL").put("toolCode", call.toolCode());
            value.set("arguments", call.arguments());
        } else {
            value.put("type", "FINISH");
        }
        return value.toString();
    }

    private void validateCitations(String answer, SnapshotRagResult rag) {
        Set<String> allowed = new TreeSet<>();
        rag.hits().forEach(hit -> allowed.add(hit.citationId()));
        var matcher = CITATION_LIKE.matcher(answer);
        while (matcher.find()) {
            var strict = CITATION.matcher(matcher.group());
            if (!strict.matches() || !allowed.contains(strict.group(1))) {
                throw new TaskExecutionAbort("AGENT_INVALID_CITATION", "Final answer contains an unknown citation");
            }
        }
    }

    private ArrayNode citations(String answer, SnapshotRagResult rag) {
        Set<String> used = new LinkedHashSet<>();
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) used.add(matcher.group(1));
        ArrayNode citations = mapper.createArrayNode();
        rag.hits().stream().filter(hit -> used.contains(hit.citationId())).forEach(hit -> citations.addObject()
                .put("citationId", hit.citationId()).put("documentId", Long.toString(hit.documentIdSnapshot()))
                .put("chunkId", Long.toString(hit.chunkIdSnapshot())).put("vectorGeneration", hit.vectorGeneration()));
        return citations;
    }

    private void phase(State state, TaskPhase phase) {
        state.boundary();
        if (!lifecycle.changePhase(state.request.taskId(), phase)) {
            state.boundary();
            throw new TaskExecutionAbort("AGENT_TASK_UNAVAILABLE", "Task is no longer executable");
        }
    }

    private void validate(TaskExecutionRequest request) {
        var snapshot = request.executionSnapshot();
        var agent = snapshot.agent();
        var model = snapshot.chatModel();
        boolean legacy = "agent-task-snapshot-v1".equals(snapshot.snapshotVersion());
        boolean current = "agent-task-snapshot-v2".equals(snapshot.snapshotVersion());
        if ((!legacy && !current) || (current && snapshot.executionSettings() == null)
                || (legacy && snapshot.executionSettings() != null)
                || !"agent-decision-json-v1".equals(snapshot.runtime().decisionProtocolVersion())
                || !TaskPromptBuilder.supportsVersion(snapshot.runtime().promptRulesVersion())
                || !"openai-compatible-default".equals(model.profileCode())
                || !Long.toString(request.agentId()).equals(agent.agentId())
                || !"ACTIVE".equals(agent.status()) || agent.systemPrompt() == null || agent.systemPrompt().isBlank()
                || agent.maxDecisionTurns() < 1 || agent.maxToolCalls() < 0
                || agent.maxToolCalls() >= agent.maxDecisionTurns()
                || request.finalTokenReserve() < 1 || request.finalTokenReserve() >= agent.maxTotalTokens()
                || model.provider() == null || model.provider().isBlank() || model.model() == null || model.model().isBlank()
                || model.contextWindow() < 1 || model.temperature() == null || model.topP() == null) {
            throw new TaskExecutionAbort("AGENT_INVALID_SNAPSHOT", "Frozen task configuration is unsupported");
        }
        if (current) {
            var settings = snapshot.executionSettings();
            if (settings.finalMaxOutputTokens() < request.finalTokenReserve()
                    || settings.finalMaxOutputTokens() > 16384
                    || settings.decisionMaxOutputTokens() < 1 || settings.decisionMaxOutputTokens() > 16384
                    || settings.modelCallTimeoutSeconds() < 1 || settings.modelCallTimeoutSeconds() > 600
                    || !Set.of("PROMPT_ONLY", "JSON_OBJECT", "JSON_SCHEMA").contains(settings.decisionResponseFormat())
                    || !Set.of("PROVIDER_DEFAULT", "DISABLED").contains(settings.thinkingMode())
                    || !"agent-execution-policy-v1".equals(settings.policyVersion())) {
                throw new TaskExecutionAbort("AGENT_INVALID_SNAPSHOT", "Frozen execution settings are unsupported");
            }
        }
        var retrieval = snapshot.retrieval();
        if (retrieval.topK() < 1 || retrieval.topK() > 100 || retrieval.similarityThreshold() == null
                || retrieval.similarityThreshold().compareTo(java.math.BigDecimal.ONE.negate()) < 0
                || retrieval.similarityThreshold().compareTo(java.math.BigDecimal.ONE) > 0
                || retrieval.useRerank() || retrieval.knowledgeBases().stream().anyMatch(kb ->
                        kb.embeddingProfileCode() == null || kb.embeddingProfileCode().isBlank())) {
            throw new TaskExecutionAbort("AGENT_INVALID_SNAPSHOT", "Frozen retrieval configuration is unsupported");
        }
        Set<String> codes = new TreeSet<>();
        Set<String> ids = new TreeSet<>();
        for (var tool : snapshot.tools()) {
            if (!codes.add(tool.toolCode()) || !ids.add(tool.toolId())) {
                throw new TaskExecutionAbort("AGENT_INVALID_SNAPSHOT", "Frozen task tools are ambiguous");
            }
        }
    }

    private String duplicateKey(ToolCallDecision decision) {
        try {
            String canonical = canonical(decision.arguments()).toString();
            return decision.toolCode() + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeSet<String> keys = new TreeSet<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> sorted.set(key, canonical(value.get(key))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }

    private StepHandle startStep(State state, StepType type, String title) {
        state.activeStep = state.recorder.startStep(type, title);
        return state.activeStep;
    }

    private void failStep(State state, StepHandle step, TaskExecutionAbort abort) {
        state.recorder.failStep(step, abort.code(), abort.getMessage());
        state.activeStep = null;
    }

    private TaskExecutionOutcome failure(State state, TaskExecutionAbort abort) {
        if (state.activeStep != null && state.recorder != null) {
            try {
                failStep(state, state.activeStep, abort);
            } catch (RuntimeException ignored) {
                // Preserve already measured usage and the original diagnostic even if Trace is unavailable.
            }
        }
        if ("TASK_CANCELLED".equals(abort.code())) {
            return TaskExecutionOutcome.cancelled(state.turns, state.toolCalls, state.usage());
        }
        if ("TASK_TIMED_OUT".equals(abort.code())) {
            return TaskExecutionOutcome.timedOut(state.turns, state.toolCalls, state.usage());
        }
        return TaskExecutionOutcome.failed("AGENT_TOKEN_BUDGET_EXHAUSTED".equals(abort.code())
                        ? TaskTerminationReason.TOKEN_BUDGET_EXHAUSTED : TaskTerminationReason.SYSTEM_ERROR,
                abort.code(), abort.getMessage(), state.turns, state.toolCalls, state.usage());
    }

    private static TaskExecutionAbort asAbort(RuntimeException ex, String fallbackCode, String fallbackMessage) {
        if (ex instanceof TaskExecutionAbort abort) return abort;
        if (ex instanceof LlmGatewayException failure) {
            return switch (failure.failureType()) {
                case OUTPUT_LIMIT -> new TaskExecutionAbort("AGENT_LLM_OUTPUT_LIMIT",
                        "Model reached its output limit before completing a response");
                case EMPTY_RESPONSE -> new TaskExecutionAbort("AGENT_LLM_EMPTY_RESPONSE",
                        "Model returned no usable response content");
                case TIMEOUT -> new TaskExecutionAbort("AGENT_LLM_TIMEOUT", "Model call exceeded its time limit");
                case PROVIDER_REJECTED -> new TaskExecutionAbort("AGENT_LLM_REJECTED", "Model service rejected the request");
                default -> new TaskExecutionAbort(fallbackCode, fallbackMessage);
            };
        }
        if (ex instanceof com.agentflow.common.error.BusinessException business) {
            return new TaskExecutionAbort(business.getErrorCode().getCode(), "Task tool validation or execution failed");
        }
        if (ex instanceof com.agentflow.tool.ToolTaskExecutionException toolFailure) {
            return new TaskExecutionAbort(toolFailure.errorCode(), "Task tool validation or execution failed");
        }
        if (ex instanceof AgentExecutionException) {
            return new TaskExecutionAbort("AGENT_INVALID_DECISION", "Model decision is invalid");
        }
        return new TaskExecutionAbort(fallbackCode, fallbackMessage);
    }

    private static TaskExecutionAbort tokenExhausted() {
        return new TaskExecutionAbort("AGENT_TOKEN_BUDGET_EXHAUSTED", "Task token budget is exhausted");
    }

    private static TaskEventRecord event(TaskEventType type, ObjectNode data) {
        return new TaskEventRecord(type, data);
    }

    private static long elapsed(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000); }
    private static String optional(String value, int limit) {
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(value.length(), limit));
    }

    private final class State {
        private final TaskExecutionRequest request;
        private final ResolvedAgentExecutionSettings settings;
        private LlmResponseSchema decisionResponseSchema;
        private ExecutionRecorder recorder;
        private StepHandle activeStep;
        private int turns;
        private int toolCalls;
        private int inputTokens;
        private int outputTokens;
        private boolean exact;
        private boolean estimated;
        private final List<JsonNode> observations = new ArrayList<>();
        private final Map<String, Integer> duplicateCounts = new HashMap<>();
        private final Map<String, ObjectNode> cachedObservations = new HashMap<>();

        State(TaskExecutionRequest request) {
            this.request = request;
            this.settings = request.executionSnapshot().executionSettings();
        }
        void boundary() {
            if (request.cancellationProbe().isCancellationRequested()) {
                throw new TaskExecutionAbort("TASK_CANCELLED", "Task cancellation was requested");
            }
            if (!clock.instant().isBefore(request.deadlineAt())) {
                throw new TaskExecutionAbort("TASK_TIMED_OUT", "Task deadline was exceeded");
            }
        }
        void account(TaskTokenUsage usage) {
            inputTokens = Math.addExact(inputTokens, usage.inputTokens());
            outputTokens = Math.addExact(outputTokens, usage.outputTokens());
            exact |= usage.quality() == TokenUsageQuality.EXACT;
            estimated |= usage.quality() == TokenUsageQuality.ESTIMATED;
        }
        TaskTokenUsage usage() {
            return new TaskTokenUsage(inputTokens, outputTokens, exact && estimated ? TokenUsageQuality.MIXED
                    : exact ? TokenUsageQuality.EXACT : estimated ? TokenUsageQuality.ESTIMATED : TokenUsageQuality.UNKNOWN);
        }
    }
}
