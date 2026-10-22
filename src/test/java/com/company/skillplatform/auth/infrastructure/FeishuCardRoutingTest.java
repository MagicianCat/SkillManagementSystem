package com.company.skillplatform.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeishuCardRoutingTest {
    private final FeishuCardProperties properties = new FeishuCardProperties();
    private final FeishuCardEnvironmentResolver resolver = new FeishuCardEnvironmentResolver(properties);

    @Test void routesExplicitEnvironmentBeforeBusinessDispatch() {
        properties.setEnvironment("testing");
        FeishuCardActionDispatcher dispatcher = mock(FeishuCardActionDispatcher.class);
        FeishuCardCallbackProxy proxy = mock(FeishuCardCallbackProxy.class);
        properties.setGatewayEnabled(true);
        var gateway = new FeishuCardCallbackGateway(properties, resolver, dispatcher, proxy);
        var testing = event("collab_approve|3|1|env=testing", Map.of("env", "testing"));
        var expected = new P2CardActionTriggerResponse();
        when(dispatcher.dispatch(testing)).thenReturn(expected);
        assertThat(gateway.handleFromFeishu(testing)).isSameAs(expected);
        verifyNoInteractions(proxy);

        var local = event("collab_approve|3|1|env=local", Map.of("env", "local"));
        assertThat(gateway.handleFromFeishu(local).getToast().getContent()).isEqualTo("目标环境尚未启用");
        verify(dispatcher, times(1)).dispatch(testing);
        verifyNoInteractions(proxy);
    }

    @Test void rejectsConflictingUnknownAndHistoricalEnvironmentsWithoutDispatch() {
        properties.setEnvironment("testing");
        FeishuCardActionDispatcher dispatcher = mock(FeishuCardActionDispatcher.class);
        FeishuCardCallbackProxy proxy = mock(FeishuCardCallbackProxy.class);
        properties.setGatewayEnabled(true);
        var gateway = new FeishuCardCallbackGateway(properties, resolver, dispatcher, proxy);
        for (var invalid : new P2CardActionTrigger[] {
                event("collab_approve|3|1|env=testing", Map.of("env", "local")),
                event("collab_approve|3|1|env=unknown", Map.of()),
                event("collab_approve|3|1", Map.of()),
                event("collab_approve|3|1|env=", Map.of()) }) {
            assertThat(gateway.handleFromFeishu(invalid).getToast().getType()).isEqualTo("error");
        }
        assertThat(gateway.handleFromFeishu(event("collab_approve|3|1", Map.of())).getToast().getContent())
                .contains("历史卡片缺少环境信息");
        verifyNoInteractions(dispatcher, proxy);
    }

    @Test void historicalTestingFallbackIsOptIn() {
        var oldCard = event("collab_approve|3|1", Map.of());
        assertThat(resolver.resolve(oldCard).valid()).isFalse();
        properties.setLegacyEnv("testing");
        assertThat(resolver.resolve(oldCard).environment()).isEqualTo("testing");
    }

    @Test void failedLocalForwardNeverRunsTestingBusiness() throws Exception {
        properties.setEnvironment("testing");
        properties.setGatewayEnabled(true);
        properties.getRouteLocal().setEnabled(true);
        FeishuCardActionDispatcher dispatcher = mock(FeishuCardActionDispatcher.class);
        FeishuCardCallbackProxy proxy = mock(FeishuCardCallbackProxy.class);
        when(proxy.forward(org.mockito.ArgumentMatchers.eq("local"), any(FeishuCardCallbackEnvelope.class)))
                .thenThrow(new java.io.IOException("tunnel closed"));
        var gateway = new FeishuCardCallbackGateway(properties, resolver, dispatcher, proxy);
        var response = gateway.handleFromFeishu(event("collab_approve|3|1|env=local", Map.of("env", "local")));
        assertThat(response.getToast().getType()).isEqualTo("error");
        assertThat(response.getToast().getContent()).contains("暂不可用");
        verifyNoInteractions(dispatcher);
    }

    @Test void signatureRejectsTamperingExpiryAndReplay() {
        var signatures = new FeishuCardInternalSignature();
        byte[] body = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
        String now = String.valueOf(Instant.now().toEpochMilli());
        String signature = signatures.sign("secret", now, "nonce-1", "local", body);
        assertThat(signatures.verifyAndConsume("secret", now, "nonce-1", "local", signature, body)).isTrue();
        assertThat(signatures.verifyAndConsume("secret", now, "nonce-1", "local", signature, body)).isFalse();
        assertThat(signatures.verifyAndConsume("secret", now, "nonce-2", "local", signature, body)).isFalse();
        assertThat(signatures.verifyAndConsume("secret", now, "nonce-1", "prod", signature, body)).isFalse();
        String old = String.valueOf(Instant.now().minusSeconds(120).toEpochMilli());
        assertThat(signatures.verifyAndConsume("secret", old, "nonce-3", "local",
                signatures.sign("secret", old, "nonce-3", "local", body), body)).isFalse();
    }

    @Test void forwardedAgentSelectionPreservesOptionAndOperator() {
        var event = event("agent_platform|session-1|env=local", Map.of());
        event.getEvent().getAction().setTag("select_static");
        event.getEvent().getAction().setOption("CODEBUDDY");
        var operator = new CallBackOperator(); operator.setOpenId("ou_actor");
        event.getEvent().setOperator(operator);
        var restored = FeishuCardCallbackEnvelope.fromSdk(event, "local").toSdk();
        assertThat(resolver.resolve(restored).environment()).isEqualTo("local");
        assertThat(restored.getEvent().getAction().getOption()).isEqualTo("CODEBUDDY");
        assertThat(restored.getEvent().getOperator().getOpenId()).isEqualTo("ou_actor");
        assertThat(FeishuCardActionTagger.stripEnvSuffix(restored.getEvent().getAction().getName()))
                .isEqualTo("agent_platform|session-1");
    }

    @Test void startupRejectsMisroutedEnvironmentAndMissingLocalTunnelSecret() {
        properties.setEnvironment("local"); properties.setActionEnabled(true);
        assertThatThrownBy(() -> properties.validate(true)).hasMessageContaining("FEISHU_EVENT_ENABLED");
        properties.setEnvironment("testing"); properties.setGatewayEnabled(true);
        properties.getRouteLocal().setEnabled(true);
        properties.getRouteLocal().setUrl("http://127.0.0.1:18080/internal/feishu/card-actions");
        assertThatThrownBy(() -> properties.validate(true)).hasMessageContaining("HMAC secret");
        properties.getSecurity().setLocalSecret("different-per-environment");
        properties.validate(true);
    }

    private static P2CardActionTrigger event(String name, Map<String, Object> value) {
        var action = new CallBackAction(); action.setName(name); action.setValue(value);
        var data = new P2CardActionTriggerData(); data.setAction(action);
        var event = new P2CardActionTrigger(); event.setEvent(data);
        return event;
    }
}
