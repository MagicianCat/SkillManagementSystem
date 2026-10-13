package com.company.skillplatform.collabproject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.DecisionRequest;
import com.company.skillplatform.common.application.BusinessException;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class CollabApprovalCardServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CollabProjectFlowService flow = mock(CollabProjectFlowService.class);
    private final CollabApprovalCardService service = new CollabApprovalCardService(jdbc, flow);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mapReviewerIdentity() {
        when(jdbc.query(startsWith("select id from iam_user"), any(RowMapper.class),
                eq("ou_reviewer"), eq("ou_reviewer"), isNull(), isNull())).thenReturn(List.of(42L));
        when(jdbc.query(startsWith("select p.project_key"), org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(), eq(900L)))
                .thenReturn("project-key");
    }

    @Test
    void mapsFeishuOperatorAndDelegatesApprovalToFlowAuthorization() {
        P2CardActionTrigger event = event("ou_reviewer", "collab_approve", 900L, 3L);

        var response = service.handle(event);

        assertThat(response.getToast().getType()).isEqualTo("success");
        verify(jdbc).query(startsWith("select id from iam_user"), any(RowMapper.class),
                eq("ou_reviewer"), eq("ou_reviewer"), isNull(), isNull());
        verify(flow).decide(eq("project-key"), eq(900L),
                eq(new DecisionRequest("APPROVE", null, 3L)), eq(42L));
    }

    @Test
    void rejectsOperatorWhoIsNotTheAssignedReviewer() {
        when(flow.decide(anyString(), anyLong(), any(DecisionRequest.class), anyLong()))
                .thenThrow(new BusinessException("COLLAB_APPROVER_REQUIRED", "reviewer required", HttpStatus.FORBIDDEN));

        var response = service.handle(event("ou_reviewer", "collab_approve", 900L, 3L));

        assertThat(response.getToast().getType()).isEqualTo("error");
        assertThat(response.getToast().getContent()).contains("无权审批");
    }

    @Test
    void rejectsAnOldApprovalVersionOrAlreadyUsedCard() {
        when(flow.decide(anyString(), anyLong(), any(DecisionRequest.class), anyLong()))
                .thenThrow(new BusinessException("COLLAB_APPROVAL_VERSION_STALE", "stale", HttpStatus.CONFLICT));

        var response = service.handle(event("ou_reviewer", "collab_approve", 900L, 2L));

        assertThat(response.getToast().getType()).isEqualTo("error");
        assertThat(response.getToast().getContent()).contains("已处理或卡片已过期");
        verify(flow).decide(eq("project-key"), eq(900L),
                eq(new DecisionRequest("APPROVE", null, 2L)), eq(42L));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rejectsUnmappedFeishuIdentityBeforeLookingUpOrDecidingSubmission() {
        when(jdbc.query(startsWith("select id from iam_user"), any(RowMapper.class),
                eq("ou_unknown"), eq("ou_unknown"), isNull(), isNull())).thenReturn(List.of());

        var response = service.handle(event("ou_unknown", "collab_approve", 900L, 3L));

        assertThat(response.getToast().getType()).isEqualTo("error");
        assertThat(response.getToast().getContent()).contains("尚未关联");
        verify(flow, never()).decide(anyString(), anyLong(), any(DecisionRequest.class), anyLong());
    }

    private P2CardActionTrigger event(String openId, String actionName, long submissionId, long approvalVersion) {
        var action = new CallBackAction();
        action.setTag("button");
        action.setName("collab_approval_approve");
        action.setValue(Map.of("action", actionName, "submissionId", submissionId, "approvalVersion", approvalVersion));
        var operator = new CallBackOperator();
        operator.setOpenId(openId);
        var data = new P2CardActionTriggerData();
        data.setOperator(operator);
        data.setAction(action);
        var event = new P2CardActionTrigger();
        event.setEvent(data);
        return event;
    }
}
