package com.agentflow.agent.binding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentBindingServiceTest {
    @Mock
    private AgentAppMapper agentAppMapper;
    @Mock
    private AgentKnowledgeBindingMapper knowledgeBindingMapper;
    @Mock
    private AgentToolBindingMapper toolBindingMapper;
    @InjectMocks
    private AgentBindingService service;

    @Test
    void shouldReplaceKnowledgeBindingsAtomicallyAfterDeduplication() {
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(agent());
        when(knowledgeBindingMapper.selectBindableOwnedKnowledgeBaseIds(
                101L,
                List.of(201L, 202L)
        )).thenReturn(List.of(202L, 201L));
        when(knowledgeBindingMapper.insert(any(AgentKnowledgeBinding.class))).thenReturn(1);

        var response = service.replaceKnowledgeBindings(
                user(),
                301L,
                new ReplaceAgentKnowledgeBindingsRequest(List.of(201L, 201L, 202L))
        );

        assertThat(response.knowledgeBaseIds()).containsExactly("201", "202");
        verify(knowledgeBindingMapper).deleteOwnedByAgent(301L, 101L);
        ArgumentCaptor<AgentKnowledgeBinding> captor = ArgumentCaptor.forClass(AgentKnowledgeBinding.class);
        verify(knowledgeBindingMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AgentKnowledgeBinding::getKnowledgeBaseId)
                .containsExactly(201L, 202L);
        assertThat(captor.getAllValues()).extracting(AgentKnowledgeBinding::getPriority)
                .containsExactly(0, 1);
        assertThat(captor.getAllValues()).extracting(AgentKnowledgeBinding::getUserId)
                .containsOnly(101L);
    }

    @Test
    void shouldRejectAnyMissingCrossOwnerDeletedOrDisabledKnowledgeBaseBeforeMutation() {
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(agent());
        when(knowledgeBindingMapper.selectBindableOwnedKnowledgeBaseIds(
                101L,
                List.of(201L, 999L)
        )).thenReturn(List.of(201L));

        assertThatThrownBy(() -> service.replaceKnowledgeBindings(
                user(),
                301L,
                new ReplaceAgentKnowledgeBindingsRequest(List.of(201L, 999L))
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_BINDING_INVALID));

        verify(knowledgeBindingMapper, never()).deleteOwnedByAgent(any(), any());
        verify(knowledgeBindingMapper, never()).insert(any(AgentKnowledgeBinding.class));
    }

    @Test
    void shouldAllowAnEmptyFullReplacementToClearBindings() {
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(agent());

        var response = service.replaceToolBindings(
                user(),
                301L,
                new ReplaceAgentToolBindingsRequest(List.of())
        );

        assertThat(response.toolIds()).isEmpty();
        verify(toolBindingMapper).deleteOwnedByAgent(301L, 101L);
        verify(toolBindingMapper, never()).selectBindableV01ToolIds(any());
        verify(toolBindingMapper, never()).insert(any(AgentToolBinding.class));
    }

    @Test
    void shouldRejectReportGenerateAndAnyOtherUnsupportedToolBeforeMutation() {
        long orderQuery = 270000000000000001L;
        long reportGenerate = 290000000000000001L;
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(agent());
        when(toolBindingMapper.selectBindableV01ToolIds(List.of(orderQuery, reportGenerate)))
                .thenReturn(List.of(orderQuery));

        assertThatThrownBy(() -> service.replaceToolBindings(
                user(),
                301L,
                new ReplaceAgentToolBindingsRequest(List.of(orderQuery, reportGenerate))
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_BINDING_INVALID));

        verify(toolBindingMapper, never()).deleteOwnedByAgent(any(), any());
        verify(toolBindingMapper, never()).insert(any(AgentToolBinding.class));
    }

    @Test
    void shouldPersistOnlyServerOwnedEnabledToolBindingsInRequestOrder() {
        long orderQuery = 270000000000000001L;
        long paymentLogQuery = 280000000000000001L;
        when(agentAppMapper.selectVisibleOwnedByIdForUpdate(301L, 101L)).thenReturn(agent());
        when(toolBindingMapper.selectBindableV01ToolIds(List.of(paymentLogQuery, orderQuery)))
                .thenReturn(List.of(orderQuery, paymentLogQuery));
        when(toolBindingMapper.insert(any(AgentToolBinding.class))).thenReturn(1);

        var response = service.replaceToolBindings(
                user(),
                301L,
                new ReplaceAgentToolBindingsRequest(List.of(paymentLogQuery, orderQuery))
        );

        assertThat(response.toolIds()).containsExactly(
                String.valueOf(paymentLogQuery),
                String.valueOf(orderQuery)
        );
        ArgumentCaptor<AgentToolBinding> captor = ArgumentCaptor.forClass(AgentToolBinding.class);
        verify(toolBindingMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getEnabled).containsOnly(true);
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getPriority).containsExactly(0, 1);
    }

    @Test
    void shouldHideAbsentCrossOwnerAndSoftDeletedAgentsBeforeBindingReads() {
        when(agentAppMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(null);

        assertThatThrownBy(() -> service.getKnowledgeBindings(user(), 301L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent not found")
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_NOT_FOUND));

        verifyNoInteractions(knowledgeBindingMapper, toolBindingMapper);
    }

    @Test
    void shouldReturnOnlyOrderedBindingIds() {
        when(agentAppMapper.selectVisibleOwnedById(301L, 101L)).thenReturn(agent());
        when(toolBindingMapper.selectBoundToolIds(301L, 101L)).thenReturn(List.of(28L, 27L));

        assertThat(service.getToolBindings(user(), 301L).toolIds()).containsExactly("28", "27");
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(101L, "owner", "Owner", "USER");
    }

    private static AgentApp agent() {
        AgentApp agent = new AgentApp();
        agent.setId(301L);
        agent.setStatus("DISABLED");
        return agent;
    }
}
