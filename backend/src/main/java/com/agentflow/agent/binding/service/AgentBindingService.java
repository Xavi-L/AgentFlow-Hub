package com.agentflow.agent.binding.service;

import com.agentflow.agent.binding.dto.AgentKnowledgeBindingsResponse;
import com.agentflow.agent.binding.dto.AgentToolBindingsResponse;
import com.agentflow.agent.binding.dto.ReplaceAgentKnowledgeBindingsRequest;
import com.agentflow.agent.binding.dto.ReplaceAgentToolBindingsRequest;
import com.agentflow.agent.binding.model.AgentKnowledgeBinding;
import com.agentflow.agent.binding.model.AgentToolBinding;
import com.agentflow.agent.binding.repository.AgentKnowledgeBindingMapper;
import com.agentflow.agent.binding.repository.AgentToolBindingMapper;
import com.agentflow.agent.model.AgentApp;
import com.agentflow.agent.repository.AgentAppMapper;
import com.agentflow.common.error.BusinessException;
import com.agentflow.common.error.ErrorCode;
import com.agentflow.user.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Full-replacement owner-scoped binding boundary for the V37 M4B slice. */
@Service
public class AgentBindingService {
    private static final int MAX_KNOWLEDGE_BINDINGS = 50;
    private static final int MAX_TOOL_BINDINGS = 20;

    private final AgentAppMapper agentAppMapper;
    private final AgentKnowledgeBindingMapper knowledgeBindingMapper;
    private final AgentToolBindingMapper toolBindingMapper;

    public AgentBindingService(
            AgentAppMapper agentAppMapper,
            AgentKnowledgeBindingMapper knowledgeBindingMapper,
            AgentToolBindingMapper toolBindingMapper
    ) {
        this.agentAppMapper = Objects.requireNonNull(agentAppMapper, "agentAppMapper must not be null");
        this.knowledgeBindingMapper = Objects.requireNonNull(
                knowledgeBindingMapper,
                "knowledgeBindingMapper must not be null"
        );
        this.toolBindingMapper = Objects.requireNonNull(
                toolBindingMapper,
                "toolBindingMapper must not be null"
        );
    }

    @Transactional(readOnly = true)
    public AgentKnowledgeBindingsResponse getKnowledgeBindings(
            AuthenticatedUser currentUser,
            Long agentId
    ) {
        requireVisibleAgent(currentUser, agentId, false);
        return AgentKnowledgeBindingsResponse.from(
                knowledgeBindingMapper.selectBoundKnowledgeBaseIds(agentId, currentUser.id())
        );
    }

    @Transactional
    public AgentKnowledgeBindingsResponse replaceKnowledgeBindings(
            AuthenticatedUser currentUser,
            Long agentId,
            ReplaceAgentKnowledgeBindingsRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        requireVisibleAgent(currentUser, agentId, true);
        List<Long> requestedIds = normalize(request.knowledgeBaseIds(), MAX_KNOWLEDGE_BINDINGS);
        if (!requestedIds.isEmpty()) {
            List<Long> validIds = knowledgeBindingMapper.selectBindableOwnedKnowledgeBaseIds(
                    currentUser.id(),
                    requestedIds
            );
            requireSameIds(requestedIds, validIds, "Knowledge base binding is invalid");
        }
        knowledgeBindingMapper.deleteOwnedByAgent(agentId, currentUser.id());
        OffsetDateTime now = OffsetDateTime.now();
        for (int priority = 0; priority < requestedIds.size(); priority++) {
            AgentKnowledgeBinding binding = new AgentKnowledgeBinding();
            binding.setUserId(currentUser.id());
            binding.setAgentId(agentId);
            binding.setKnowledgeBaseId(requestedIds.get(priority));
            binding.setPriority(priority);
            binding.setCreatedAt(now);
            binding.setUpdatedAt(now);
            requireSingleInsert(knowledgeBindingMapper.insert(binding));
        }
        return AgentKnowledgeBindingsResponse.from(requestedIds);
    }

    @Transactional(readOnly = true)
    public AgentToolBindingsResponse getToolBindings(
            AuthenticatedUser currentUser,
            Long agentId
    ) {
        requireVisibleAgent(currentUser, agentId, false);
        return AgentToolBindingsResponse.from(
                toolBindingMapper.selectBoundToolIds(agentId, currentUser.id())
        );
    }

    @Transactional
    public AgentToolBindingsResponse replaceToolBindings(
            AuthenticatedUser currentUser,
            Long agentId,
            ReplaceAgentToolBindingsRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        requireVisibleAgent(currentUser, agentId, true);
        List<Long> requestedIds = normalize(request.toolIds(), MAX_TOOL_BINDINGS);
        if (!requestedIds.isEmpty()) {
            List<Long> validIds = toolBindingMapper.selectBindableV01ToolIds(requestedIds);
            requireSameIds(requestedIds, validIds, "Tool binding is invalid");
        }
        toolBindingMapper.deleteOwnedByAgent(agentId, currentUser.id());
        OffsetDateTime now = OffsetDateTime.now();
        for (int priority = 0; priority < requestedIds.size(); priority++) {
            AgentToolBinding binding = new AgentToolBinding();
            binding.setUserId(currentUser.id());
            binding.setAgentId(agentId);
            binding.setToolId(requestedIds.get(priority));
            binding.setEnabled(true);
            binding.setPriority(priority);
            binding.setCreatedAt(now);
            binding.setUpdatedAt(now);
            requireSingleInsert(toolBindingMapper.insert(binding));
        }
        return AgentToolBindingsResponse.from(requestedIds);
    }

    private AgentApp requireVisibleAgent(
            AuthenticatedUser currentUser,
            Long agentId,
            boolean lock
    ) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        if (agentId == null || agentId <= 0) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "agentId must be positive");
        }
        AgentApp agent = lock
                ? agentAppMapper.selectVisibleOwnedByIdForUpdate(agentId, currentUser.id())
                : agentAppMapper.selectVisibleOwnedById(agentId, currentUser.id());
        if (agent == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "Agent not found");
        }
        return agent;
    }

    private static List<Long> normalize(List<Long> ids, int maxSize) {
        Objects.requireNonNull(ids, "binding IDs must not be null");
        if (ids.size() > maxSize) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "Too many binding IDs");
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "Binding IDs must be positive");
            }
            normalized.add(id);
        }
        return List.copyOf(normalized);
    }

    private static void requireSameIds(List<Long> requested, List<Long> valid, String message) {
        Set<Long> requestedSet = Set.copyOf(requested);
        Set<Long> validSet = Set.copyOf(valid);
        if (!requestedSet.equals(validSet)) {
            throw new BusinessException(ErrorCode.AGENT_BINDING_INVALID, message);
        }
    }

    private static void requireSingleInsert(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one inserted Agent binding row");
        }
    }
}
