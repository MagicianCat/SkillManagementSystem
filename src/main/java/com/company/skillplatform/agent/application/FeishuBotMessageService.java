package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.infrastructure.entity.*;
import com.company.skillplatform.agent.infrastructure.repository.*;
import com.company.skillplatform.auth.application.FeishuAuthService;
import com.company.skillplatform.auth.infrastructure.FeishuBotClient;
import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.*;
import com.lark.oapi.event.cardcallback.model.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent inbox and delivery workflow for Feishu bot messages. */
@Service
public class FeishuBotMessageService {
    private static final Logger log = LoggerFactory.getLogger(FeishuBotMessageService.class);
    private static final Pattern MENTION = Pattern.compile("<at[^>]*>.*?</at>", Pattern.DOTALL);
    private final FeishuBotMessageRepository messages;
    private final FeishuBotBindingRepository bindings;
    private final AgentSessionRepository sessions;
    private final AgentRunRepository runs;
    private final AgentMessageRepository agentMessages;
    private final IamUserRepository users;
    private final IamRolePermissionRepository permissions;
    private final AgentConversationService conversation;
    private final FeishuBotClient bot;
    private final FeishuAuthService auth;
    private final FeishuProperties feishu;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String webBaseUrl;
    private final int maxRetries;

    public FeishuBotMessageService(FeishuBotMessageRepository messages, FeishuBotBindingRepository bindings,
            AgentSessionRepository sessions, AgentRunRepository runs, AgentMessageRepository agentMessages,
            IamUserRepository users, IamRolePermissionRepository permissions, AgentConversationService conversation,
            FeishuBotClient bot, FeishuAuthService auth, FeishuProperties feishu, ObjectMapper json,
            @Value("${skill-platform.feishu.bot-enabled:false}") boolean enabled,
            @Value("${skill-platform.feishu.bot-web-base-url:http://127.0.0.1:5173}") String webBaseUrl,
            @Value("${skill-platform.feishu.bot-max-retries:3}") int maxRetries) {
        this.messages=messages; this.bindings=bindings; this.sessions=sessions; this.runs=runs; this.agentMessages=agentMessages;
        this.users=users; this.permissions=permissions; this.conversation=conversation; this.bot=bot; this.auth=auth; this.feishu=feishu;
        this.json=json; this.enabled=enabled; this.webBaseUrl=webBaseUrl.replaceAll("/$", ""); this.maxRetries=maxRetries;
    }

    @Transactional
    public void accept(P2MessageReceiveV1 event) {
        if (!enabled || event == null || event.getEvent() == null || event.getEvent().getMessage() == null) return;
        EventMessage message = event.getEvent().getMessage();
        if (!"text".equalsIgnoreCase(message.getMessageType()) || blank(message.getMessageId())) return;
        if (messages.findByMessageId(message.getMessageId()).isPresent()) return;
        String chatType = text(message.getChatType(), "p2p").toLowerCase(Locale.ROOT);
        String chatId = text(message.getChatId(), "");
        if (chatId.isBlank()) return;
        String content = textContent(message.getContent());
        if (content.isBlank()) return;
        String threadKey = threadKey(message, chatType);
        String device = deviceType(message.getUserAgent());
        String sender = senderId(event.getEvent().getSender());
        IamUserEntity user = sender == null ? null : users.findByFeishuOpenId(sender).or(() -> users.findByFeishuUserId(sender)).orElse(null);
        FeishuBotMessageEntity saved = messages.save(new FeishuBotMessageEntity(message.getMessageId(), event.getRequestId(),
                user == null ? null : user.getId(), chatId, chatType, threadKey, content, message.getUserAgent(), device));
        log.info("event=feishu.bot.message.accepted messageId={} chatType={} deviceType={} userFound={}", message.getMessageId(), chatType, device, user != null);
    }

    @Transactional
    public P2CardActionTriggerResponse handleCardAction(P2CardActionTrigger event) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        response.setToast(toast);
        try {
            if (event == null || event.getEvent() == null || event.getEvent().getAction() == null) {
                return toast(response, toast, "error", "无效的筛选操作");
            }
            CallBackOperator operator = event.getEvent().getOperator();
            String sender = operator == null ? null : first(operator.getOpenId(), operator.getUserId(), operator.getUnionId());
            IamUserEntity user = sender == null ? null : users.findByFeishuOpenId(sender).or(() -> users.findByFeishuUserId(sender)).orElse(null);
            CallBackAction action = event.getEvent().getAction();
            String name = text(action.getName(), "");
            int separator = name.indexOf('|');
            if (user == null || separator < 0 || !name.startsWith("agent_")) return toast(response, toast, "error", "无法识别当前会话");
            String kind = name.substring(6, separator);
            String sessionKey = name.substring(separator + 1);
            AgentSessionEntity session = sessions.findBySessionKeyAndOwnerUserId(sessionKey, user.getId()).orElse(null);
            if (session == null || session.isDeleted()) return toast(response, toast, "error", "会话已失效，请重新发起对话");
            String selected = text(action.getOption(), "").trim().toUpperCase(Locale.ROOT);
            if ("platform".equals(kind) && !List.of("CODEBUDDY", "OPENCODE").contains(selected)) return toast(response, toast, "error", "不支持的平台");
            if ("os".equals(kind) && !List.of("ANY", "WINDOWS", "MACOS", "LINUX").contains(selected)) return toast(response, toast, "error", "不支持的操作系统");
            conversation.updateContext(sessionKey, user.getId(), "platform".equals(kind) ? selected : session.getPlatform(), "os".equals(kind) ? selected : session.getOsType());
            return toast(response, toast, "success", "筛选已更新，将从下一轮对话生效");
        } catch (BusinessException error) {
            return toast(response, toast, "error", "当前会话正在处理，请稍后再修改");
        } catch (Exception error) {
            log.warn("event=feishu.bot.context_update.failed error={}", error.getClass().getSimpleName());
            return toast(response, toast, "error", "筛选更新失败，请稍后重试");
        }
    }

    @Scheduled(fixedDelayString = "${skill-platform.feishu.bot-task-interval-ms:2000}")
    public void processReady() {
        if (!enabled) return;
        List<FeishuBotMessageEntity> ready = messages.findReady(List.of("RECEIVED", "RUN_SUBMITTED"), Instant.now(), PageRequest.of(0, 50));
        for (FeishuBotMessageEntity message : ready) {
            try { if ("RECEIVED".equals(message.getStatus())) processInbound(message.getId()); else deliverRun(message.getId()); }
            catch (Exception error) { fail(message.getId(), "BOT_PROCESSING_FAILED"); }
        }
    }

    @Transactional
    public void processInbound(Long id) {
        FeishuBotMessageEntity message = messages.findById(id).orElse(null);
        if (message == null || !"RECEIVED".equals(message.getStatus())) return;
        IamUserEntity user = message.getOwnerUserId() == null ? null : users.findById(message.getOwnerUserId()).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE || !permissions.findPermissionKeysByUserId(user.getId()).contains("skill:browse")) {
            replyText(message, "当前飞书账号尚未加入平台或没有使用研途助手的权限，请联系管理员。", false);
            return;
        }
        boolean group = "group".equalsIgnoreCase(message.getChatType()) || "topic".equalsIgnoreCase(message.getChatType());
        if (!group && !"AUTHORIZED".equals(auth.documentAccessStatus(user.getId()))) {
            replyAuthorization(message);
            return;
        }
        if (isNewSession(message.getContent())) {
            AgentSessionEntity created = createOrReplaceBinding(message, user.getId(), group ? "PLATFORM_PUBLIC_ONLY" : "USER_VISIBLE");
            replyContextCard(message, created, "你好！我是研途助手，你的研发全流程助手。\n\n我可以：\n- 根据研发需求推荐你有权限访问的平台 Skill\n- 基于你有权限查看的飞书云文档回答公司内部业务问题，并标明来源\n- 结合已有知识回答研发基础问题\n\n我不会编造答案，无法确认的内容会明确说明。请先选择平台和操作系统，再发送问题；也可以直接在问题中说明。", group);
            return;
        }
        AgentSessionEntity session = sessionFor(message, user.getId(), group ? "PLATFORM_PUBLIC_ONLY" : "USER_VISIBLE");
        try {
            AgentConversationService.AcceptedRun accepted = conversation.send(session.getSessionKey(), user.getId(), message.getContent());
            message.bound(bindingFor(message, user.getId()).getId(), session.getSessionKey(), accepted.runKey());
            messages.save(message);
            acknowledge(message, group);
        } catch (BusinessException error) {
            if ("AGENT_RUN_ACTIVE".equals(error.getCode())) replyText(message, "上一条问题仍在处理中，请稍后再发送。", group);
            else replyText(message, "当前问题暂时无法处理，请稍后重试。", group);
        }
    }

    @Transactional
    public void deliverRun(Long id) {
        FeishuBotMessageEntity message = messages.findById(id).orElse(null);
        if (message == null || !"RUN_SUBMITTED".equals(message.getStatus())) return;
        AgentRunEntity run = runs.findByRunKey(message.getAgentRunKey()).orElse(null);
        if (run == null || "PENDING".equals(run.getStatus()) || "RUNNING".equals(run.getStatus())) return;
        AgentSessionEntity session = sessions.findBySessionKey(message.getAgentSessionKey()).orElse(null);
        if (session == null) { fail(id, "AGENT_SESSION_NOT_FOUND"); return; }
        if (!"SUCCEEDED".equals(run.getStatus())) { replyText(message, "研途助手处理失败，请稍后重试。", "group".equalsIgnoreCase(message.getChatType())); return; }
        AgentConversationService.SessionView result = conversation.get(session.getSessionKey(), message.getOwnerUserId());
        String answer = result.messages().stream().filter(item -> "ASSISTANT".equals(item.role())).reduce((first, second) -> second).map(AgentConversationService.MessageView::content).orElse("Agent 未返回可展示的回答。");
        replyResult(message, answer, result.latestRecommendation(), message.getAgentRunKey(), session, "group".equalsIgnoreCase(message.getChatType()));
    }

    private AgentSessionEntity sessionFor(FeishuBotMessageEntity message, Long userId, String scope) {
        FeishuBotBindingEntity binding = bindingFor(message, userId);
        if (binding != null) return sessions.findById(binding.getSessionId()).orElseGet(() -> createOrReplaceBinding(message, userId, scope));
        return createOrReplaceBinding(message, userId, scope);
    }
    private AgentSessionEntity createOrReplaceBinding(FeishuBotMessageEntity message, Long userId, String scope) {
        String key = scopeKey(message); FeishuBotBindingEntity binding = bindings.findByOwnerUserIdAndScopeKey(userId, key).orElse(null);
        if (binding != null) sessions.findById(binding.getSessionId()).ifPresent(old -> { old.close(); sessions.save(old); });
        AgentSessionEntity session = sessions.findBySessionKey(conversation.create(userId, "skill-advisor", null, null, "FEISHU_BOT", scope).session().sessionKey()).orElseThrow();
        if (binding == null) binding = new FeishuBotBindingEntity(userId, key, message.getChatId(), message.getChatType(), message.getThreadKey(), session.getId(), message.getDeviceType());
        else { binding.replaceSession(session.getId(), message.getDeviceType()); }
        bindings.save(binding); return session;
    }
    private FeishuBotBindingEntity bindingFor(FeishuBotMessageEntity message, Long userId) { return bindings.findByOwnerUserIdAndScopeKey(userId, scopeKey(message)).orElse(null); }
    private String scopeKey(FeishuBotMessageEntity message) { return ("group".equalsIgnoreCase(message.getChatType()) ? "group:" : "p2p:") + message.getChatId() + ":" + message.getThreadKey(); }

    private void replyAuthorization(FeishuBotMessageEntity message) {
        Map<String,Object> card = card("需要授权飞书文档", List.of(Map.of("tag", "markdown", "content", "首次使用机器人需要完成飞书文档授权。"), button("完成授权", auth.authorize("/agent"))));
        send(message, "interactive", card, false);
    }
    private void acknowledge(FeishuBotMessageEntity message, boolean thread) {
        try { bot.reply(message.getMessageId(), "text", Map.of("text", "已收到，正在处理中。"), "sms-ack-" + message.getMessageId(), thread); }
        catch (Exception error) { log.warn("event=feishu.bot.ack.failed messageId={}", message.getMessageId()); }
    }
    private void replyResult(FeishuBotMessageEntity message, String answer, AgentConversationService.RecommendationView recommendation, String runKey, AgentSessionEntity session, boolean group) {
        List<Object> elements = new ArrayList<>(); elements.add(Map.of("tag", "markdown", "content", truncate(answer, 12000)));
        elements.add(Map.of("tag", "hr")); elements.add(Map.of("tag", "markdown", "content", targetSummary(session)));
        elements.add(contextSelect("平台", "agent_platform|" + session.getSessionKey(), session.getPlatform(), List.of(option("CodeBuddy", "CODEBUDDY"), option("OpenCode", "OPENCODE"))));
        elements.add(contextSelect("操作系统", "agent_os|" + session.getSessionKey(), session.getOsType(), List.of(option("通用", "ANY"), option("Windows", "WINDOWS"), option("macOS", "MACOS"), option("Linux", "LINUX"))));
        if (recommendation != null && !recommendation.items().isEmpty()) {
            elements.add(Map.of("tag", "hr")); elements.add(Map.of("tag", "markdown", "content", "**推荐 Skill**"));
            for (Map<String,Object> item : recommendation.items()) {
                String key = String.valueOf(item.getOrDefault("skillKey", "")); String name = String.valueOf(item.getOrDefault("displayName", key));
                elements.add(Map.of("tag", "markdown", "content", "- **" + name + "**：" + truncate(String.valueOf(item.getOrDefault("reason", "")), 500)));
                if (!key.isBlank()) elements.add(button("查看 Skill", skillUrls(key, item)));
            }
            if (runKey != null && !runKey.isBlank()) elements.add(button("批量下载推荐 Skill", webBaseUrl + "/agent?runKey=" + runKey + "&batchDownload=1"));
        }
        elements.add(button("在研途助手开启新会话", webBaseUrl + "/agent?newSession=1"));
        send(message, "interactive", Map.of("schema", "2.0", "body", Map.of("elements", elements)), group);
    }
    private void replyContextCard(FeishuBotMessageEntity message, AgentSessionEntity session, String prompt, boolean thread) {
        List<Object> elements = new ArrayList<>();
        elements.add(Map.of("tag", "markdown", "content", prompt));
        elements.add(contextSelect("平台", "agent_platform|" + session.getSessionKey(), session.getPlatform(), List.of(option("CodeBuddy", "CODEBUDDY"), option("OpenCode", "OPENCODE"))));
        elements.add(contextSelect("操作系统", "agent_os|" + session.getSessionKey(), session.getOsType(), List.of(option("通用", "ANY"), option("Windows", "WINDOWS"), option("macOS", "MACOS"), option("Linux", "LINUX"))));
        send(message, "interactive", card("选择运行环境", elements), thread);
    }
    private Map<String,Object> contextSelect(String label, String name, String selected, List<Map<String,Object>> options) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("tag", "select_static"); value.put("name", name);
        value.put("placeholder", Map.of("tag", "plain_text", "content", label)); value.put("options", options);
        if (selected != null && !selected.isBlank()) value.put("initial_option", selected);
        return value;
    }
    private Map<String,Object> option(String label, String value) { return Map.of("text", Map.of("tag", "plain_text", "content", label), "value", value); }
    private String targetSummary(AgentSessionEntity session) { return "**当前筛选**：平台 " + Objects.requireNonNullElse(session.getPlatform(), "未选择") + "；系统 " + Objects.requireNonNullElse(session.getOsType(), "未选择") + "。本轮消息中的明确要求会自动覆盖这里的选择。"; }
    private P2CardActionTriggerResponse toast(P2CardActionTriggerResponse response, CallBackToast toast, String type, String content) { toast.setType(type); toast.setContent(content); return response; }
    private Map<String,Object> card(String title, List<Object> elements) { return Map.of("schema", "2.0", "header", Map.of("title", Map.of("tag", "plain_text", "content", title)), "body", Map.of("elements", elements)); }
    private Map<String,Object> button(String label, String url) { return Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", label), "type", "primary", "multi_url", Map.of("url", url, "pc_url", url)); }
    private Map<String,Object> button(String label, Map<String,String> urls) { return Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", label), "type", "primary", "multi_url", urls); }
    private Map<String,String> skillUrls(String key, Map<String,Object> item) { String query = targetQuery(item); String web = webBaseUrl + "/skills/" + key + query; String mobile = webBaseUrl + "/m/skills/" + key + query; return Map.of("url", mobile, "pc_url", web, "ios_url", mobile, "android_url", mobile); }
    private String targetQuery(Map<String,Object> item) { String platform=String.valueOf(item.getOrDefault("platform", "")), os=String.valueOf(item.getOrDefault("osType", "")); if(platform.isBlank()||os.isBlank())return ""; return "?platform="+platform+"&osType="+os; }
    private void replyText(FeishuBotMessageEntity message, String value, boolean thread) { send(message, "text", Map.of("text", value), thread); }
    private void send(FeishuBotMessageEntity message, String type, Object content, boolean thread) { try { message.replied(bot.reply(message.getMessageId(), type, content, "sms-" + message.getMessageId(), thread)); messages.save(message); } catch (Exception error) { fail(message.getId(), "BOT_REPLY_FAILED"); } }
    private void fail(Long id, String code) { messages.findById(id).ifPresent(message -> { if (message.getRetryCount() < maxRetries) message.failed(code, Instant.now().plusSeconds(10L * (message.getRetryCount() + 1))); else message.failed(code, null); messages.save(message); }); }
    private String textContent(String raw) { try { JsonNode node=json.readTree(raw); return MENTION.matcher(node.path("text").asText(raw)).replaceAll("").trim(); } catch (Exception e) { return MENTION.matcher(raw == null ? "" : raw).replaceAll("").trim(); } }
    private String threadKey(EventMessage message, String chatType) { if ("p2p".equals(chatType)) return "p2p"; return first(message.getThreadId(), message.getRootId(), message.getMessageId()); }
    private String senderId(EventSender sender) { if (sender == null || sender.getSenderId() == null) return null; return first(sender.getSenderId().getOpenId(), sender.getSenderId().getUserId(), sender.getSenderId().getUnionId()); }
    private String deviceType(String agent) { if (agent == null) return "UNKNOWN"; String lower=agent.toLowerCase(Locale.ROOT); return lower.contains("android") || lower.contains("iphone") || lower.contains("ipad") || lower.contains("mobile") ? "MOBILE" : lower.contains("windows") || lower.contains("mac") || lower.contains("linux") ? "DESKTOP" : "UNKNOWN"; }
    private boolean isNewSession(String content) { String value=content.trim(); return "/new".equalsIgnoreCase(value) || "新会话".equals(value) || "开启新会话".equals(value); }
    private String truncate(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "\n\n内容较长，完整结果请打开平台查看。"; }
    private static String first(String... values) { for(String value:values) if(value!=null&&!value.isBlank()) return value; return ""; }
    private static String text(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    private static boolean blank(String value){return value==null||value.isBlank();}
}
