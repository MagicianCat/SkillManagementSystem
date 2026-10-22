package com.company.skillplatform.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeishuCardCallbackControllerTest {
    private final FeishuCardProperties properties = new FeishuCardProperties();
    private final FeishuCardInternalSignature signatures = new FeishuCardInternalSignature();
    private final FeishuCardActionDispatcher dispatcher = mock(FeishuCardActionDispatcher.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private FeishuCardCallbackController controller;

    @BeforeEach void setup() {
        properties.setEnvironment("local");
        properties.setActionEnabled(true);
        properties.getSecurity().setLocalSecret("local-test-secret");
        controller = new FeishuCardCallbackController(properties, signatures,
                new FeishuCardEnvironmentResolver(properties), dispatcher, mapper);
    }

    @Test void signedCallbackExecutesInTargetAndReturnsBusinessToast() throws Exception {
        var business = new P2CardActionTriggerResponse();
        var toast = new CallBackToast(); toast.setType("success"); toast.setContent("已批准");
        business.setToast(toast);
        when(dispatcher.dispatch(any())).thenReturn(business);
        byte[] body = body("local", "local");
        var result = send(body, "local", true);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(((FeishuCardCallbackEnvelope.Response) result.getBody()).toast().content()).isEqualTo("已批准");
        verify(dispatcher).dispatch(any());
    }

    @Test void badSignatureAndEnvironmentMismatchCannotReachBusiness() throws Exception {
        byte[] valid = body("local", "local");
        assertThat(send(valid, "local", false).getStatusCode().value()).isEqualTo(401);
        assertThat(send(body("prod", "local"), "local", true).getStatusCode().value()).isEqualTo(403);
        assertThat(send(body("local", "testing"), "local", true).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(dispatcher);
    }

    private byte[] body(String target, String cardEnvironment) throws Exception {
        var action = new CallBackAction();
        action.setTag("button"); action.setName("collab_approve|7|1|env=" + cardEnvironment);
        action.setValue(Map.of("action", "collab_approve", "submissionId", 7,
                "approvalVersion", 1, "env", cardEnvironment));
        var data = new P2CardActionTriggerData(); data.setAction(action);
        var event = new P2CardActionTrigger(); event.setEvent(data);
        return mapper.writeValueAsBytes(FeishuCardCallbackEnvelope.fromSdk(event, target));
    }

    private org.springframework.http.ResponseEntity<?> send(byte[] body, String environment, boolean validSignature) {
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String signature = validSignature
                ? signatures.sign("local-test-secret", timestamp, nonce, environment, body)
                : "v1=bad";
        return controller.dispatch(body, environment, timestamp, nonce, signature);
    }
}
