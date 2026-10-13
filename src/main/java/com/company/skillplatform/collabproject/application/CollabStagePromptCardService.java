package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.common.application.BusinessException;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Sends one prompt from a stage handoff card as a standalone message to that project's chat. */
@Service
public class CollabStagePromptCardService {
    private static final Logger log = LoggerFactory.getLogger(CollabStagePromptCardService.class);
    private static final String ACTION = "collab_send_prompt";

    private final JdbcTemplate jdbc;
    private final CollabFeishuChatGateway chats;
    private final CollabProjectAuthorizationService authorization;

    public CollabStagePromptCardService(JdbcTemplate jdbc, CollabFeishuChatGateway chats,
                                       CollabProjectAuthorizationService authorization) {
        this.jdbc = jdbc;
        this.chats = chats;
        this.authorization = authorization;
    }

    public boolean handles(P2CardActionTrigger event) {
        if (event == null || event.getEvent() == null || event.getEvent().getAction() == null) return false;
        var action = event.getEvent().getAction();
        var value = action.getValue();
        String name = action.getName();
        return ACTION.equals(name) || (name != null && name.startsWith(ACTION + "|"))
                || (value != null && ACTION.equals(value.get("action")))
                || (value != null && value.containsKey("projectKey") && value.containsKey("stageId")
                && value.containsKey("attemptNo") && value.containsKey("promptType"));
    }

    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        response.setToast(toast);
        try {
            if (!handles(event)) return result(response, toast, "error", "提示词卡片无效");
            var callback = event.getEvent();
            var operator = callback.getOperator();
            String openId = operator == null ? null : operator.getOpenId();
            String feishuUserId = operator == null ? null : operator.getUserId();
            if (blank(openId) && blank(feishuUserId)) return result(response, toast, "error", "无法识别飞书操作者");
            List<Long> actors = jdbc.query("select id from iam_user where status='ACTIVE' and ((? is not null and feishu_open_id=?) or (? is not null and feishu_user_id=?)) limit 2",
                    (rs, n) -> rs.getLong(1), openId, openId, feishuUserId, feishuUserId);
            if (actors.size() != 1) return result(response, toast, "error", "飞书账号尚未关联唯一的研枢用户");

            Map<String, Object> value = callback.getAction().getValue();
            String callbackName = callback.getAction().getName();
            if ((value == null || !value.containsKey("projectKey")) && callbackName != null
                    && callbackName.startsWith(ACTION + "|")) {
                String[] parts = callbackName.split("\\|", -1);
                if (parts.length == 5) {
                    value = Map.of("projectKey", parts[1], "stageId", parseLong(parts[2]),
                            "attemptNo", parseLong(parts[3]), "promptType", parts[4]);
                }
            }
            if (value == null) return result(response, toast, "error", "提示词卡片无效，请刷新项目页面");
            String projectKey = String.valueOf(value.getOrDefault("projectKey", ""));
            long stageId = parseLong(value.get("stageId"));
            int attemptNo = Math.toIntExact(parseLong(value.get("attemptNo")));
            String promptType = String.valueOf(value.getOrDefault("promptType", "")).toUpperCase(Locale.ROOT);
            var project = authorization.requireMemberOrThrow(projectKey, actors.get(0));
            Map<String, Object> stage = jdbc.query("select s.title,s.stage_order,s.git_required from collab_stage s where s.id=? and s.project_id=?",
                    rs -> rs.next() ? Map.of("title", rs.getString("title"), "stage_order", rs.getInt("stage_order"), "git_required", rs.getBoolean("git_required")) : null,
                    stageId, project.projectId());
            if (stage == null) return result(response, toast, "error", "阶段已不存在，请打开项目页面");
            if (attemptNo < 1) return result(response, toast, "error", "提示词卡片已失效");

            String prompt = switch (promptType) {
                case "CONTEXT" -> {
                    if (((Number) stage.get("stage_order")).intValue() <= 1) yield null;
                    yield CollabStagePromptFactory.context(projectKey, String.valueOf(stage.get("title")));
                }
                case "SUBMIT" -> CollabStagePromptFactory.submit(projectKey, String.valueOf(stage.get("title")),
                        stageId, attemptNo, Boolean.TRUE.equals(stage.get("git_required")));
                default -> null;
            };
            if (prompt == null) return result(response, toast, "error", "提示词卡片已失效，请刷新项目页面");
            String chatId = jdbc.query("select chat_id from collab_feishu_binding where project_id=?",
                    rs -> rs.next() ? rs.getString(1) : null, project.projectId());
            if (blank(chatId)) return result(response, toast, "error", "项目群暂不可用，请稍后重试");
            chats.sendText(chatId, prompt, "collab-prompt-" + UUID.randomUUID());
            return result(response, toast, "success", "已单独发送到项目群");
        } catch (BusinessException denied) {
            return result(response, toast, "error", "当前飞书账号无权在此项目群发送提示词");
        } catch (Exception failure) {
            log.warn("event=collab.prompt.card.send.failed error={}", failure.getClass().getSimpleName());
            return result(response, toast, "error", "发送失败，请稍后重试");
        }
    }

    private P2CardActionTriggerResponse result(P2CardActionTriggerResponse response, CallBackToast toast,
                                               String type, String message) {
        toast.setType(type);
        toast.setContent(message);
        return response;
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private boolean blank(String value) { return value == null || value.isBlank() || "null".equals(value); }
}
