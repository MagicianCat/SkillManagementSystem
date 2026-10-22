package com.company.skillplatform.collabproject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.auth.infrastructure.FeishuCardProperties;
import com.company.skillplatform.auth.infrastructure.FeishuCardActionTagger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class CollabFeishuWorkerCardTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CollabFeishuChatGateway chats = mock(CollabFeishuChatGateway.class);
    private final FeishuProperties feishu = new FeishuProperties(true, "app", "secret", null,
            false, null, null, null, null, null, true, java.time.Duration.ofMinutes(5));
    private CollabFeishuWorker worker;
    private Map<String, Object> stage;

    @BeforeEach void setup() {
        worker = new CollabFeishuWorker(jdbc, chats, null, feishu, new ObjectMapper(),
                "http://localhost:5173", null, null, null, null);
        stage = new HashMap<>(Map.of("name", "需求一", "project_key", "req-1",
                "inherited_template_version", 1, "group_name", "项目组一", "stage_key", "PRODUCT",
                "stage_title", "产品设计", "stage_order", 2, "attempt_no", 1, "git_required", false));
        when(jdbc.queryForMap(startsWith("SELECT chat_id,"), eq(1L)))
                .thenReturn(Map.of("chat_id", "chat-1"));
        when(jdbc.queryForMap(startsWith("SELECT p.name,"), eq(2L), eq(1L))).thenReturn(stage);
        when(jdbc.queryForList(contains("a.duty='OWNER'"), eq(2L))).thenReturn(List.of(
                Map.of("role_name", "产品经理", "display_name", "负责人", "feishu_open_id", "ou_owner")));
    }

    @Test void asdmHandoffMentionsOnlyOwnersAndHidesBothPrompts() {
        stage.put("asdm_branch", "feature/req-1");
        ReflectionTestUtils.invokeMethod(worker, "stageHandoff", 1L, 2L, 3L);
        String card = sentCard();
        assertThat(card).contains("阶段已开启", "负责人：", "产品经理：<at id=ou_owner>", "ASDM", "hr")
                .doesNotContain("审批人", "project-context-fetch", "project-artifact-submit", "单独发送", "{local file}");
    }

    @Test void nonAsdmHandoffKeepsBothPromptsWithoutMentioningReviewers() {
        ReflectionTestUtils.invokeMethod(worker, "stageHandoff", 1L, 2L, 3L);
        assertThat(sentCard()).contains("project-context-fetch", "project-artifact-submit", "单独发送")
                .doesNotContain("审批人");
    }

    @Test void localCardKeepsCallbacksWithEventConnectionDisabled() {
        var noEvent = new FeishuProperties(true, "app", "secret", null,
                false, null, null, null, null, null, false, java.time.Duration.ofMinutes(5));
        var cards = new FeishuCardProperties(); cards.setEnvironment("local"); cards.setActionEnabled(true);
        worker = new CollabFeishuWorker(jdbc, chats, null, noEvent, new ObjectMapper(),
                "http://localhost:5173", null, null, null, null, cards, new FeishuCardActionTagger(cards));
        ReflectionTestUtils.invokeMethod(worker, "stageHandoff", 1L, 2L, 3L);
        assertThat(sentCard()).contains("单独发送", "|env=local", "env=local");
    }

    @Test void asdmAcceptanceHidesPromptsWithoutClaimingAsdmMonitorsAcceptance() {
        stage.put("asdm_branch", "feature/req-1");
        stage.put("stage_key", "ACCEPTANCE");
        stage.put("stage_title", "验收");
        ReflectionTestUtils.invokeMethod(worker, "stageHandoff", 1L, 2L, 3L);
        assertThat(sentCard()).contains("验收要求").doesNotContain("ASDM 正在监测", "project-artifact-submit");
    }

    @Test void asdmReworkHidesPromptAndKeepsOwnerRoleAndRejectionReason() {
        stage.put("asdm_branch", "feature/req-1");
        stage.put("decision_comment", "请补充说明");
        ReflectionTestUtils.invokeMethod(worker, "stageRework", 1L, 2L, 3L);
        assertThat(sentCard()).contains("阶段需重做", "产品经理：<at id=ou_owner>", "请补充说明", "重新发送发布确认卡")
                .doesNotContain("project-artifact-submit", "单独发送");
    }

    @Test void rosterGroupsRolesAndDeduplicatesPeopleWithinEachRole() {
        var owner = Map.<String,Object>of("role_name", "产品经理", "display_name", "甲", "feishu_open_id", "ou_a");
        var other = Map.<String,Object>of("role_name", "需求分析员", "display_name", "甲", "feishu_open_id", "ou_a");
        assertThat(CollabFeishuCardLayout.roster(List.of(owner, owner, other), "指定审批人"))
                .isEqualTo("产品经理：<at id=ou_a></at>\n需求分析员：<at id=ou_a></at>");
    }

    @Test void approvalCardListsEachReviewerRoleAndUsesChineseStageTitle() {
        stage.putAll(Map.of("approval_id", 9L, "state", "PENDING", "publish_status", "PUBLISHED",
                "feishu_url", "https://example.com/wiki/artifact", "submission_id", 8L, "title", "阶段交付物"));
        when(jdbc.queryForList(startsWith("SELECT r.requirement_role_id"), eq(8L))).thenReturn(List.of(
                Map.of("role_name", "产品经理", "display_name", "甲", "feishu_open_id", "ou_product"),
                Map.of("role_name", "需求分析员", "display_name", "乙", "feishu_open_id", "ou_requirement")));
        ReflectionTestUtils.invokeMethod(worker, "approvalReminder", 1L, 2L, true);
        assertThat(sentCard()).contains("待审批 · 产品设计", "审批人：", "产品经理：<at id=ou_product>",
                "需求分析员：<at id=ou_requirement>", "https://example.com/wiki/artifact")
                .doesNotContain("阶段 **PRODUCT**", "审批人员");
    }

    private String sentCard() {
        ArgumentCaptor<Object> card = ArgumentCaptor.forClass(Object.class);
        verify(chats).sendInteractive(eq("chat-1"), card.capture(), anyString());
        return card.getValue().toString();
    }
}
