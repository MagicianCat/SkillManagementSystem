package com.company.skillplatform.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.agent.infrastructure.entity.AgentRunEntity;
import com.company.skillplatform.agent.infrastructure.entity.AgentSessionEntity;
import com.company.skillplatform.agent.infrastructure.repository.AgentMessageRepository;
import com.company.skillplatform.agent.infrastructure.repository.AgentRecommendationRepository;
import com.company.skillplatform.agent.infrastructure.repository.AgentRunRepository;
import com.company.skillplatform.agent.infrastructure.repository.AgentSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentConversationServiceContextTest {
    private final AgentSessionRepository sessions=mock(AgentSessionRepository.class);
    private final AgentRunRepository runs=mock(AgentRunRepository.class);
    private final AgentMessageRepository messages=mock(AgentMessageRepository.class);
    private final AgentTargetContextResolver resolver=mock(AgentTargetContextResolver.class);
    private AgentConversationService service;
    private AgentSessionEntity session;

    @BeforeEach void setUp(){
        service=new AgentConversationService(sessions,runs,messages,mock(AgentRecommendationRepository.class),
                mock(AgentRunService.class),mock(AgentRuntimeClient.class),mock(AgentEventHub.class),mock(AgentMcpAuditService.class),
                mock(ExecutorService.class),new ObjectMapper(),"test","model",resolver);
        session=new AgentSessionEntity(7L,"skill-advisor","OPENCODE","WINDOWS");
        when(sessions.findBySessionKeyAndOwnerUserId(session.getSessionKey(),7L)).thenReturn(Optional.of(session));
        when(runs.findFirstBySessionAndStatusInOrderByRunNoDesc(any(),any())).thenReturn(Optional.empty());
        when(runs.countBySession(session)).thenReturn(0L);
        when(messages.countBySession(session)).thenReturn(0L);
        when(runs.save(any(AgentRunEntity.class))).thenAnswer(invocation->invocation.getArgument(0));
    }

    @Test void explicitRequestContextIsAuthoritativeAndSkipsTextInference(){
        var accepted=service.send(session.getSessionKey(),7L,"请推荐 Windows 的 OpenCode skill","codebuddy","macos",true);
        assertThat(accepted.platform()).isEqualTo("CODEBUDDY");
        assertThat(accepted.osType()).isEqualTo("MACOS");
        verify(resolver,never()).resolve(any());
    }

    @Test void absentRequestContextKeepsExistingInferenceBehavior(){
        when(resolver.resolve("use linux")).thenReturn(new AgentTargetContextResolver.Resolution(false,null,true,"LINUX"));
        var accepted=service.send(session.getSessionKey(),7L,"use linux");
        assertThat(accepted.platform()).isEqualTo("OPENCODE");
        assertThat(accepted.osType()).isEqualTo("LINUX");
        verify(resolver).resolve("use linux");
    }
}
