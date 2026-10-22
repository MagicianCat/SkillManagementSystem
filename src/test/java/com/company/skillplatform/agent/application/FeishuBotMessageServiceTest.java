package com.company.skillplatform.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.company.skillplatform.agent.infrastructure.entity.FeishuBotMessageEntity;
import com.company.skillplatform.agent.infrastructure.repository.*;
import com.company.skillplatform.auth.application.FeishuAuthService;
import com.company.skillplatform.auth.infrastructure.FeishuBotClient;
import com.company.skillplatform.user.infrastructure.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FeishuBotMessageServiceTest {
    private final FeishuBotMessageRepository messages = mock(FeishuBotMessageRepository.class);
    private final AgentConversationService conversation = mock(AgentConversationService.class);
    private final FeishuBotClient bot = mock(FeishuBotClient.class);

    @Test
    void groupMessagesAreNotAcceptedAsAgentConversations() {
        service().accept(event("group"));

        verifyNoInteractions(messages, conversation, bot);
    }

    @Test
    void privateMessagesRemainAvailableToTheAgent() {
        service().accept(event("p2p"));

        verify(messages).save(argThat(message -> "p2p".equals(message.getChatType())
                && "RECEIVED".equals(message.getStatus())));
    }

    @Test
    void queuedGroupMessagesAreDiscardedWithoutCreatingThreads() {
        FeishuBotMessageEntity inbound = new FeishuBotMessageEntity("old-inbound", "event", 1L,
                "chat", "group", "thread", "feedback", null, "UNKNOWN");
        when(messages.findById(1L)).thenReturn(Optional.of(inbound));

        service().processInbound(1L);

        assertThat(inbound.getStatus()).isEqualTo("IGNORED");
        verify(messages).save(inbound);
        verifyNoInteractions(conversation, bot);
    }

    @Test
    void completedGroupRunsCannotReplyAfterDeployment() {
        FeishuBotMessageEntity pending = new FeishuBotMessageEntity("old-run", "event", 1L,
                "chat", "group", "thread", "feedback", null, "UNKNOWN");
        pending.bound(1L, "session", "run");
        when(messages.findById(1L)).thenReturn(Optional.of(pending));

        service().deliverRun(1L);

        assertThat(pending.getStatus()).isEqualTo("IGNORED");
        verify(messages).save(pending);
        verifyNoInteractions(conversation, bot);
    }

    private FeishuBotMessageService service() {
        return new FeishuBotMessageService(messages, mock(FeishuBotBindingRepository.class),
                mock(AgentSessionRepository.class), mock(AgentRunRepository.class),
                mock(AgentMessageRepository.class), mock(IamUserRepository.class),
                mock(IamRolePermissionRepository.class), conversation, bot,
                mock(FeishuAuthService.class), null, new ObjectMapper(),
                true, "http://127.0.0.1:5173", 3);
    }

    private P2MessageReceiveV1 event(String chatType) {
        EventMessage message = new EventMessage();
        message.setMessageId("new-group-message");
        message.setMessageType("text");
        message.setChatType(chatType);
        message.setChatId("unbound-on-this-instance");
        message.setContent("{\"text\":\"free discussion\"}");
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(message);
        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        event.setEvent(data);
        return event;
    }
}
