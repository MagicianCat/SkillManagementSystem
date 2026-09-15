package com.company.skillplatform.auth.infrastructure;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.contact.ContactService;
import com.lark.oapi.service.contact.v3.model.P2DepartmentCreatedV3;
import com.lark.oapi.service.contact.v3.model.P2DepartmentDeletedV3;
import com.lark.oapi.service.contact.v3.model.P2DepartmentUpdatedV3;
import com.lark.oapi.service.contact.v3.model.P2UserCreatedV3;
import com.lark.oapi.service.contact.v3.model.P2UserDeletedV3;
import com.lark.oapi.service.contact.v3.model.P2UserUpdatedV3;
import com.lark.oapi.ws.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Owns the Feishu SDK WebSocket connection for the lifetime of this service.
 *
 * <p>The connection is deliberately optional. OAuth and the existing
 * directory API continue to work when event transport is disabled.</p>
 */
@Component
public class FeishuLongConnection implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnection.class);

    private final FeishuProperties properties;
    private final com.company.skillplatform.agent.application.FeishuBotMessageService botMessages;
    private volatile Client client;
    private volatile boolean running;

    public FeishuLongConnection(FeishuProperties properties, com.company.skillplatform.agent.application.FeishuBotMessageService botMessages) {
        this.properties = properties; this.botMessages = botMessages;
    }

    @Override
    public synchronized void start() {
        if (running || !properties.eventEnabled()) {
            return;
        }
        validateConfiguration();
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                        return botMessages.handleCardAction(event);
                    }
                })
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override public void handle(P2MessageReceiveV1 event) { botMessages.accept(event); }
                })
                .onP2UserCreatedV3(new ContactService.P2UserCreatedV3Handler() {
                    @Override
                    public void handle(P2UserCreatedV3 event) {
                        log.info("event=feishu.directory.user.created");
                    }
                })
                .onP2UserUpdatedV3(new ContactService.P2UserUpdatedV3Handler() {
                    @Override
                    public void handle(P2UserUpdatedV3 event) {
                        log.info("event=feishu.directory.user.updated");
                    }
                })
                .onP2UserDeletedV3(new ContactService.P2UserDeletedV3Handler() {
                    @Override
                    public void handle(P2UserDeletedV3 event) {
                        log.info("event=feishu.directory.user.deleted");
                    }
                })
                .onP2DepartmentCreatedV3(new ContactService.P2DepartmentCreatedV3Handler() {
                    @Override
                    public void handle(P2DepartmentCreatedV3 event) {
                        log.info("event=feishu.directory.department.created");
                    }
                })
                .onP2DepartmentUpdatedV3(new ContactService.P2DepartmentUpdatedV3Handler() {
                    @Override
                    public void handle(P2DepartmentUpdatedV3 event) {
                        log.info("event=feishu.directory.department.updated");
                    }
                })
                .onP2DepartmentDeletedV3(new ContactService.P2DepartmentDeletedV3Handler() {
                    @Override
                    public void handle(P2DepartmentDeletedV3 event) {
                        log.info("event=feishu.directory.department.deleted");
                    }
                })
                .build();
        client = new Client.Builder(properties.appId(), properties.appSecret())
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .source("skill-management-system")
                .onReconnecting(() -> log.warn("event=feishu.websocket.reconnecting appId={}",
                        maskAppId(properties.appId())))
                .onReconnected(() -> log.info("event=feishu.websocket.reconnected appId={}",
                        maskAppId(properties.appId())))
                .build();

        Client current = client;
        current.start();
        try {
            current.awaitReady(10_000);
        } catch (Exception error) {
            running = false;
            client = null;
            current.close();
            log.error("event=feishu.websocket.connection.failed appId={} error={}",
                    maskAppId(properties.appId()), rootMessage(error));
            throw new IllegalStateException("Unable to establish Feishu WebSocket connection", error);
        }
        running = true;
        log.info("event=feishu.websocket.connected appId={}", maskAppId(properties.appId()));
        log.info("event=feishu.websocket.starting appId={}", maskAppId(properties.appId()));
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        Client current = client;
        client = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
            log.info("event=feishu.websocket.disconnected appId={}", maskAppId(properties.appId()));
        } catch (Exception error) {
            log.warn("event=feishu.websocket.disconnect.failed appId={} error={}",
                    maskAppId(properties.appId()), rootMessage(error));
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void validateConfiguration() {
        if (isBlank(properties.appId()) || isBlank(properties.appSecret())) {
            throw new IllegalStateException(
                    "FEISHU_EVENT_ENABLED=true requires FEISHU_APP_ID and FEISHU_APP_SECRET");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String maskAppId(String appId) {
        if (isBlank(appId)) {
            return "<empty>";
        }
        return appId.length() <= 8 ? "***" : appId.substring(0, 6) + "***";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }
}
