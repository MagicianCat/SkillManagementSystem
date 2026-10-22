package com.company.skillplatform.auth.infrastructure;

import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FeishuCardCallbackGateway {
    private static final Logger log = LoggerFactory.getLogger(FeishuCardCallbackGateway.class);
    private final FeishuCardProperties properties;
    private final FeishuCardEnvironmentResolver resolver;
    private final FeishuCardActionDispatcher dispatcher;
    private final FeishuCardCallbackProxy proxy;

    public FeishuCardCallbackGateway(FeishuCardProperties properties, FeishuCardEnvironmentResolver resolver,
            FeishuCardActionDispatcher dispatcher, FeishuCardCallbackProxy proxy) {
        this.properties = properties; this.resolver = resolver; this.dispatcher = dispatcher; this.proxy = proxy;
    }

    public P2CardActionTriggerResponse handleFromFeishu(P2CardActionTrigger event) {
        if (!properties.isGatewayEnabled()) return error("卡片回调网关尚未启用");
        FeishuCardEnvironmentResolver.Resolution resolution = resolver.resolve(event);
        if (!resolution.valid()) {
            log.warn("event=feishu.card_action.route_rejected reason={}", resolution.reason());
            if ("legacy_environment_missing".equals(resolution.reason())) {
                return error("历史卡片缺少环境信息，请使用新卡片操作");
            }
            return error("卡片环境无法识别，请刷新卡片");
        }
        String environment = resolution.environment();
        if ("testing".equals(environment) && "testing".equals(properties.getEnvironment())) {
            return dispatcher.dispatch(event);
        }
        FeishuCardProperties.Route route = route(environment);
        if (route == null || !route.isEnabled()) {
            log.warn("event=feishu.card_action.route_disabled target={}", environment);
            return error("目标环境尚未启用");
        }
        long started = System.nanoTime();
        try {
            var result = proxy.forward(environment, FeishuCardCallbackEnvelope.fromSdk(event, environment));
            log.info("event=feishu.card_action.forwarded target={} callbackId={} elapsedMs={} result=ok",
                    environment, safeCallbackId(event), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (Exception failure) {
            log.warn("event=feishu.card_action.forward_failed target={} callbackId={} elapsedMs={} errorType={}",
                    environment, safeCallbackId(event), (System.nanoTime() - started) / 1_000_000, failure.getClass().getSimpleName());
            return error("目标环境暂不可用，请稍后重试");
        }
    }

    private FeishuCardProperties.Route route(String env) {
        return switch (env) { case "local" -> properties.getRouteLocal(); case "prod" -> properties.getRouteProd(); default -> null; };
    }
    private static String safeCallbackId(P2CardActionTrigger event) {
        String id = event == null ? null : event.getRequestId(); return id == null ? "" : id;
    }
    public static P2CardActionTriggerResponse error(String content) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast(); toast.setType("error"); toast.setContent(content); response.setToast(toast);
        return response;
    }
}
