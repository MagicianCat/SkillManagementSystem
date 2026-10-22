package com.company.skillplatform.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/feishu/card-actions")
public class FeishuCardCallbackController {
    private static final Logger log = LoggerFactory.getLogger(FeishuCardCallbackController.class);
    private final FeishuCardProperties properties;
    private final FeishuCardInternalSignature signatures;
    private final FeishuCardEnvironmentResolver resolver;
    private final FeishuCardActionDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public FeishuCardCallbackController(FeishuCardProperties properties, FeishuCardInternalSignature signatures,
            FeishuCardEnvironmentResolver resolver, FeishuCardActionDispatcher dispatcher, ObjectMapper objectMapper) {
        this.properties = properties; this.signatures = signatures; this.resolver = resolver;
        this.dispatcher = dispatcher; this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> dispatch(@RequestBody byte[] rawBody,
            @RequestHeader(value = "X-SMS-Callback-Env", required = false) String headerEnvironment,
            @RequestHeader(value = "X-SMS-Callback-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-SMS-Callback-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-SMS-Callback-Signature", required = false) String signature) {
        String ownEnvironment = properties.getEnvironment();
        String secret = switch (ownEnvironment) {
            case "local" -> properties.getSecurity().getLocalSecret();
            case "prod" -> properties.getSecurity().getProdSecret();
            default -> "";
        };
        if (!properties.isActionEnabled() || !ownEnvironment.equals(headerEnvironment)
                || !signatures.verifyAndConsume(secret, timestamp, nonce, headerEnvironment, signature, rawBody)) {
            log.warn("event=feishu.card_action.internal_rejected environment={} reason=signature_or_environment", ownEnvironment);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_internal_signature"));
        }

        final FeishuCardCallbackEnvelope envelope;
        try {
            envelope = objectMapper.readValue(rawBody, FeishuCardCallbackEnvelope.class);
            if (envelope.version() != 1 || envelope.event() == null || !ownEnvironment.equals(envelope.targetEnvironment())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "environment_mismatch"));
            }
        } catch (Exception invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_callback_envelope"));
        }

        final com.lark.oapi.event.cardcallback.model.P2CardActionTrigger event;
        try {
            event = envelope.toSdk();
        } catch (Exception invalid) {
            log.warn("event=feishu.card_action.internal_failed environment={} callbackId={} errorType={}", ownEnvironment,
                    envelope.callbackId() == null ? "" : envelope.callbackId(), invalid.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_callback_event"));
        }
        var resolution = resolver.resolve(event);
        if (!resolution.valid() || !ownEnvironment.equals(resolution.environment())) {
            log.warn("event=feishu.card_action.internal_rejected environment={} reason=card_environment_mismatch", ownEnvironment);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "card_environment_mismatch"));
        }
        try {
            P2CardActionTriggerResponse result = dispatcher.dispatch(event);
            return ResponseEntity.ok(FeishuCardCallbackEnvelope.fromSdk(result));
        } catch (Exception failure) {
            log.error("event=feishu.card_action.internal_failed environment={} callbackId={} errorType={}", ownEnvironment,
                    envelope.callbackId() == null ? "" : envelope.callbackId(), failure.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "callback_dispatch_failed"));
        }
    }
}
