package com.company.skillplatform.auth.infrastructure;

import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.util.List;
import java.util.Map;

/** Versioned, SDK-independent wire shape used only between SMS environments. */
public record FeishuCardCallbackEnvelope(int version, String targetEnvironment, String callbackId,
                                         Event event) {
    public record Event(Operator operator, String token, String host, String deliveryType,
                        Action action, Context context) { }
    public record Operator(String tenantKey, String userId, String openId, String unionId) { }
    public record Action(String tag, String name, Map<String, Object> value, String option,
                         String timezone, Map<String, Object> formValue, String inputValue,
                         List<String> options, Boolean checked) { }
    public record Context(String url, String previewToken, String openMessageId, String openChatId) { }
    public record Response(Toast toast, Map<String, Object> card) { }
    public record Toast(String type, String content, Map<String, String> i18n) { }

    public static FeishuCardCallbackEnvelope fromSdk(P2CardActionTrigger source, String targetEnvironment) {
        if (source == null || source.getEvent() == null) throw new IllegalArgumentException("Missing callback event");
        var data = source.getEvent();
        var operator = data.getOperator();
        var sdkAction = data.getAction();
        var context = data.getContext();
        return new FeishuCardCallbackEnvelope(1, targetEnvironment, source.getRequestId(), new Event(
                operator == null ? null : new Operator(operator.getTenantKey(), operator.getUserId(), operator.getOpenId(), operator.getUnionId()),
                data.getToken(), data.getHost(), data.getDeliveryType(),
                sdkAction == null ? null : new Action(sdkAction.getTag(), sdkAction.getName(), sdkAction.getValue(),
                        sdkAction.getOption(), sdkAction.getTimezone(), sdkAction.getFormValue(), sdkAction.getInputValue(),
                        sdkAction.getOptions(), sdkAction.getChecked()),
                context == null ? null : new Context(context.getUrl(), context.getPreviewToken(), context.getOpenMessageId(), context.getOpenChatId())));
    }

    public P2CardActionTrigger toSdk() {
        if (version != 1 || event == null) throw new IllegalArgumentException("Unsupported callback envelope");
        P2CardActionTriggerData data = new P2CardActionTriggerData();
        data.setToken(event.token()); data.setHost(event.host()); data.setDeliveryType(event.deliveryType());
        if (event.operator() != null) {
            CallBackOperator operator = new CallBackOperator();
            operator.setTenantKey(event.operator().tenantKey()); operator.setUserId(event.operator().userId());
            operator.setOpenId(event.operator().openId()); operator.setUnionId(event.operator().unionId());
            data.setOperator(operator);
        }
        if (event.action() != null) {
            Action a = event.action(); CallBackAction action = new CallBackAction();
            action.setTag(a.tag()); action.setName(a.name()); action.setValue(a.value()); action.setOption(a.option());
            action.setTimezone(a.timezone()); action.setFormValue(a.formValue()); action.setInputValue(a.inputValue());
            action.setOptions(a.options()); action.setChecked(a.checked()); data.setAction(action);
        }
        if (event.context() != null) {
            Context c = event.context(); CallBackContext context = new CallBackContext();
            context.setUrl(c.url()); context.setPreviewToken(c.previewToken()); context.setOpenMessageId(c.openMessageId());
            context.setOpenChatId(c.openChatId()); data.setContext(context);
        }
        P2CardActionTrigger trigger = new P2CardActionTrigger(); trigger.setEvent(data);
        return trigger;
    }

    public static Response fromSdk(P2CardActionTriggerResponse response) {
        if (response == null) return new Response(null, null);
        var toast = response.getToast();
        Map<String, Object> card = null;
        if (response.getCard() != null) {
            card = new java.util.LinkedHashMap<>();
            card.put("type", response.getCard().getType()); card.put("data", response.getCard().getData());
        }
        return new Response(toast == null ? null : new Toast(toast.getType(), toast.getContent(), toast.getI18n()), card);
    }

    public static P2CardActionTriggerResponse toSdkResponse(Response source) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        if (source != null && source.toast() != null) {
            var data = source.toast(); var toast = new com.lark.oapi.event.cardcallback.model.CallBackToast();
            toast.setType(data.type()); toast.setContent(data.content()); toast.setI18n(data.i18n()); response.setToast(toast);
        }
        if (source != null && source.card() != null) {
            CallBackCard card = new CallBackCard(); card.setType((String) source.card().get("type"));
            card.setData(source.card().get("data")); response.setCard(card);
        }
        return response;
    }
}
