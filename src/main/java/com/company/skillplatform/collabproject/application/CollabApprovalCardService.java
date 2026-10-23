package com.company.skillplatform.collabproject.application;

import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.DecisionRequest;
import com.company.skillplatform.common.application.BusinessException;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Handles only collaboration approval buttons received over the authenticated Feishu SDK event connection. */
@Service
public class CollabApprovalCardService {
    private static final Logger log = LoggerFactory.getLogger(CollabApprovalCardService.class);
    private static final String APPROVE_ACTION_PREFIX = "collab_approve|";
    private static final String REJECT_ACTION_PREFIX = "collab_reject|";

    private final JdbcTemplate jdbc;
    private final CollabProjectFlowService flow;

    public CollabApprovalCardService(JdbcTemplate jdbc, CollabProjectFlowService flow) {
        this.jdbc = jdbc;
        this.flow = flow;
    }

    public boolean handles(P2CardActionTrigger event) {
        if (event == null || event.getEvent() == null || event.getEvent().getAction() == null) return false;
        var action = event.getEvent().getAction();
        Object actionType = action.getValue() == null ? null : action.getValue().get("action");
        String name = action.getName();
        return "collab_approve".equals(actionType) || "collab_reject".equals(actionType)
                || name != null && (name.startsWith(APPROVE_ACTION_PREFIX) || name.startsWith(REJECT_ACTION_PREFIX));
    }

    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        response.setToast(toast);
        try {
            if (!handles(event)) return result(response, toast, "error", "审批卡片无效");
            var callback = event.getEvent();
            var operator = callback.getOperator();
            String openId = operator == null ? null : operator.getOpenId();
            String userId = operator == null ? null : operator.getUserId();
            if ((openId == null || openId.isBlank()) && (userId == null || userId.isBlank()))
                return result(response, toast, "error", "无法识别飞书操作者，请打开研枢审批页处理");
            List<Long> actors = jdbc.query("select id from iam_user where status='ACTIVE' and ((? is not null and feishu_open_id=?) or (? is not null and feishu_user_id=?)) limit 2",
                    (rs, n) -> rs.getLong(1), openId, openId, userId, userId);
            if (actors.size() != 1) return result(response, toast, "error", "飞书账号尚未关联唯一的研枢用户");
            long submissionId;
            long expectedVersion;
            String decision;
            try {
                var action = callback.getAction();
                String callbackName = action.getName();
                Object actionValue = action.getValue() == null ? null : action.getValue().get("action");
                if (action.getValue() != null && ("collab_approve".equals(actionValue) || "collab_reject".equals(actionValue))) {
                    submissionId = parseLong(action.getValue().get("submissionId"));
                    expectedVersion = parseLong(action.getValue().get("approvalVersion"));
                    decision = "collab_reject".equals(actionValue) ? "REJECT" : "APPROVE";
                } else if (callbackName != null && (callbackName.startsWith(APPROVE_ACTION_PREFIX) || callbackName.startsWith(REJECT_ACTION_PREFIX))) {
                    String[] parts = callbackName.split("\\|", -1);
                    if (parts.length != 3) return result(response, toast, "error", "审批卡片已失效，请打开研枢审批页");
                    submissionId = Long.parseLong(parts[1]);
                    expectedVersion = Long.parseLong(parts[2]);
                    decision = callbackName.startsWith(REJECT_ACTION_PREFIX) ? "REJECT" : "APPROVE";
                } else {
                    return result(response, toast, "error", "审批卡片已失效，请打开研枢审批页");
                }
                if (submissionId < 1 || expectedVersion < 0) throw new NumberFormatException();
            } catch (RuntimeException invalid) {
                return result(response, toast, "error", "审批卡片已失效，请打开研枢审批页");
            }
            String projectKey = jdbc.query("select p.project_key from collab_submission sub join collab_stage s on s.id=sub.stage_id join collab_project p on p.id=s.project_id where sub.id=?",
                    rs -> rs.next() ? rs.getString(1) : null, submissionId);
            if (projectKey == null) return result(response, toast, "error", "审批事项不存在或已失效");
            flow.decide(projectKey, submissionId, new DecisionRequest(decision, null, expectedVersion), actors.get(0));
            return result(response, toast, "success", "REJECT".equals(decision) ? "已驳回，阶段已退回重做" : "已批准，阶段状态已更新");
        } catch (BusinessException denied) {
            String message = switch (denied.getCode()) {
                case "COLLAB_APPROVER_REQUIRED", "COLLAB_PROJECT_MEMBER_REQUIRED", "COLLAB_PROJECT_NOT_FOUND" -> "当前飞书账号无权审批此事项";
                case "COLLAB_SUBMISSION_ALREADY_DECIDED", "COLLAB_APPROVAL_VERSION_STALE" -> "此审批已处理或卡片已过期，请刷新项目页面";
                default -> "审批未完成，请打开研枢审批页查看详情";
            };
            return result(response, toast, "error", message);
        } catch (Exception failure) {
            log.warn("event=collab.approval.card.failed error={}", failure.getClass().getSimpleName());
            return result(response, toast, "error", "审批未完成，请打开研枢审批页重试");
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
}
