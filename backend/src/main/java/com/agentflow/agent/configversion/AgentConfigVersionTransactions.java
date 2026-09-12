package com.agentflow.agent.configversion;

import static com.agentflow.agent.AgentKnowledgeLimits.MAX_KNOWLEDGE_BINDINGS;

import com.agentflow.agent.binding.repository.AgentKnowledgeBindingMapper;
import com.agentflow.agent.binding.repository.AgentToolBindingMapper;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.agent.snapshot.AgentTaskSnapshotResolver;
import com.agentflow.common.api.PageRequest;
import com.agentflow.common.api.PageResult;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** All draft and binding reads share the task/publication repeatable-read transaction and Agent lock. */
@Service
public class AgentConfigVersionTransactions {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(?:\\b(?:authorization|proxy[-_ ]authorization|cookie|set[-_ ]cookie|api[-_ ]?key|"
            + "x[-_ ]api[-_ ]key|password|secret|client[-_ ]secret|access[-_ ]token|refresh[-_ ]token|"
            + "connection[-_ ]?string|jdbc[-_ ]?url)\\b[\\\"']?\\s*[:=]|\\bBearer\\s+\\S+|"
            + "https?://[^\\s/@:]+:[^\\s/@]+@|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
    private final AgentAppMapper agents;
    private final AgentKnowledgeBindingMapper knowledge;
    private final AgentToolBindingMapper tools;
    private final AgentConfigVersionMapper versions;
    private final AgentTaskSnapshotResolver snapshots;
    private final ObjectMapper json;
    private final Clock clock;

    public AgentConfigVersionTransactions(AgentAppMapper agents, AgentKnowledgeBindingMapper knowledge,
            AgentToolBindingMapper tools, AgentConfigVersionMapper versions, AgentTaskSnapshotResolver snapshots,
            ObjectMapper json, Clock clock) {
        this.agents = agents; this.knowledge = knowledge; this.tools = tools; this.versions = versions;
        this.snapshots = snapshots; this.json = json; this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Published capture(long userId, long agentId) {
        AgentApp agent = visible(userId, agentId, true);
        snapshots.validateConfigurationAgent(agentId, agent);
        requireNoCredentials(agent.getSystemPrompt());
        requireNoCredentials(agent.getModelName());
        AgentConfiguration configuration = AgentConfiguration.capture(agent,
                knowledge.selectConfigurationBindings(agentId, userId), tools.selectConfigurationBindings(agentId, userId));
        validateBindings(userId, configuration);
        var tree = json.valueToTree(configuration);
        String hash = ConfigCanonicalJson.sha256(tree);
        AgentConfigVersion existing = versions.selectContent(userId, agentId, hash);
        if (existing != null) return new Published(existing, false);
        AgentConfigVersion version = new AgentConfigVersion();
        version.setId(IdWorker.getId()); version.setUserId(userId); version.setAgentId(agentId);
        version.setSchemaVersion(AgentConfiguration.SCHEMA_VERSION);
        version.setHashAlgorithmVersion(ConfigCanonicalJson.ALGORITHM_VERSION);
        version.setConfigHash(hash); version.setConfigJson(ConfigCanonicalJson.canonicalJson(tree));
        version.setCreatedAt(OffsetDateTime.now(clock));
        if (versions.insert(version) == 1) {
            // PostgreSQL owns timestamp precision. Return the same persisted representation as GET/reuse.
            return new Published(requiredVersion(userId, agentId, version.getId()), true);
        }
        // This may occur under a compatible transaction; REPEATABLE_READ races instead raise 40001
        // and the non-transactional caller retries from a new complete database view.
        existing = versions.selectContent(userId, agentId, hash);
        if (existing == null) throw new IllegalStateException("Configuration content conflict was not visible");
        return new Published(existing, false);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AgentConfigVersion selectForTask(long userId, long agentId, Long configVersionId) {
        if (configVersionId == null) return capture(userId, agentId).version();
        visible(userId, agentId, true);
        return requiredVersion(userId, agentId, configVersionId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AgentConfigVersionResponse get(long userId, long agentId, long versionId) {
        visible(userId, agentId, false);
        return response(requiredVersion(userId, agentId, versionId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<AgentConfigVersionResponse> list(long userId, long agentId, PageRequest requestedPage) {
        visible(userId, agentId, false);
        PageRequest page = requestedPage == null ? new PageRequest() : requestedPage;
        return PageResult.of(versions.selectPage(userId, agentId, page.getPageSize(),
                (long) (page.getPage() - 1) * page.getPageSize()).stream().map(this::response).toList(),
                page.getPage(), page.getPageSize(), versions.count(userId, agentId));
    }

    public AgentConfiguration configuration(AgentConfigVersion version) {
        try {
            return json.readValue(version.getConfigJson(), AgentConfiguration.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored configuration could not be decoded", e);
        }
    }

    public AgentConfigVersionResponse response(AgentConfigVersion version) {
        try {
            return new AgentConfigVersionResponse(version.getId().toString(), version.getAgentId().toString(),
                    version.getSchemaVersion(), version.getHashAlgorithmVersion(), version.getConfigHash(),
                    json.readTree(version.getConfigJson()), version.getCreatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored configuration could not be decoded", e);
        }
    }

    private void validateBindings(long userId, AgentConfiguration config) {
        List<Long> kbIds = config.orderedKnowledgeIds(), toolIds = config.orderedEnabledToolIds();
        if (kbIds.size() > MAX_KNOWLEDGE_BINDINGS || config.toolBindings().size() > 20)
            throw new BusinessException(ErrorCode.AGENT_BINDING_INVALID, "Too many Agent bindings");
        if (!kbIds.isEmpty() && !Set.copyOf(kbIds).equals(Set.copyOf(
                knowledge.selectBindableOwnedKnowledgeBaseIds(userId, kbIds))))
            throw new BusinessException(ErrorCode.AGENT_BINDING_INVALID, "Knowledge base binding is invalid");
        if (!toolIds.isEmpty() && !Set.copyOf(toolIds).equals(Set.copyOf(tools.selectBindableV01ToolIds(toolIds))))
            throw new BusinessException(ErrorCode.AGENT_BINDING_INVALID, "Tool binding is invalid");
        // Definition hard validation is performed again when a task resolves its actual snapshot.
        if (!toolIds.isEmpty()) snapshots.validateToolDefinitions(tools.selectSelectedSnapshotTools(toolIds));
    }

    private AgentApp visible(long userId, long agentId, boolean lock) {
        if (userId <= 0 || agentId <= 0)
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "IDs must be positive");
        AgentApp agent = lock ? agents.selectVisibleOwnedByIdForSnapshot(agentId, userId)
                : agents.selectVisibleOwnedById(agentId, userId);
        if (agent == null) throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found");
        return agent;
    }

    private AgentConfigVersion requiredVersion(long userId, long agentId, long id) {
        if (id <= 0) throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "configVersionId must be positive");
        AgentConfigVersion result = versions.selectOwned(userId, agentId, id);
        if (result == null) throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Configuration version not found");
        return result;
    }

    static void requireNoCredentials(String text) {
        if (text != null && CREDENTIAL.matcher(text).find())
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "Configuration must not contain credentials");
    }

    public record Published(AgentConfigVersion version, boolean created) { }
}
