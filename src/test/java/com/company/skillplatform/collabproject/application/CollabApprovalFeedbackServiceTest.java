package com.company.skillplatform.collabproject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService.ProjectRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class CollabApprovalFeedbackServiceTest {
    private static final String REQUIREMENT = "requirement-key";
    private static final long ACTOR = 41L;
    private static final long PROJECT = 700L;
    private static final long STAGE = 701L;
    private static final long SUBMISSION = 702L;
    private static final long FROZEN_VERSION = 703L;
    private static final long LATEST_VERSION = 799L;
    private static final String CHAT = "oc_approval_chat";
    private static final Instant START = Instant.parse("2026-10-21T08:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CollabProjectAuthorizationService authorization = mock(CollabProjectAuthorizationService.class);
    private final FeishuApprovalFeedbackGateway feishu = mock(FeishuApprovalFeedbackGateway.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CollabApprovalFeedbackService service =
            new CollabApprovalFeedbackService(jdbc, authorization, feishu, json);

    @BeforeEach
    void setUp() {
        when(authorization.requireMemberOrThrow(REQUIREMENT, ACTOR)).thenReturn(new ProjectRef(
                PROJECT, REQUIREMENT, ACTOR, "L2", "ACTIVE", 1, 3, 10L,
                "group-key", ACTOR, "PUBLISHED", ACTOR, null, false, false));
        when(jdbc.queryForList(contains("frozen_at is not null"), eq(STAGE), eq(ACTOR)))
                .thenReturn(List.of(Map.of("user_id", ACTOR)));
        when(jdbc.queryForList(contains("FEISHU_PUBLISHED"), eq(PROJECT), eq("REQUIREMENT")))
                .thenReturn(List.of(roundRow()));
        when(jdbc.queryForList(contains("FEISHU_PUBLISHED"), eq(PROJECT), eq(SUBMISSION)))
                .thenReturn(List.of(roundRow()));
    }

    @Test
    void rejectsNonOwnerOfTheResolvedStage() {
        when(jdbc.queryForList(contains("frozen_at is not null"), eq(STAGE), eq(ACTOR))).thenReturn(List.of());

        assertThatThrownBy(() -> service.context(REQUIREMENT, "REQUIREMENT", ACTOR))
                .hasMessageContaining("frozen owner of this stage");

        verify(jdbc).queryForList(contains("FEISHU_PUBLISHED"), eq(PROJECT), eq("REQUIREMENT"));
    }

    @Test
    void rejectsUnsupportedStage() {
        assertThatThrownBy(() -> service.context(REQUIREMENT, "UNKNOWN", ACTOR))
                .hasMessageContaining("Unsupported stage key");

        verifyNoInteractions(authorization);
        verify(jdbc, never()).queryForList(contains("FEISHU_PUBLISHED"), eq(PROJECT), eq("UNKNOWN"));
    }

    @Test
    void artifactsAreBoundToSubmissionSnapshotRatherThanLatestVersion() {
        Map<String, Object> result = service.artifacts(REQUIREMENT, SUBMISSION, ACTOR);

        verify(jdbc).query(contains("p.artifact_version_id=?"), any(RowCallbackHandler.class), eq(FROZEN_VERSION));
        verify(jdbc).query(contains("p.submission_id=?"), any(RowCallbackHandler.class), eq(SUBMISSION));
        assertThat(FROZEN_VERSION).isNotEqualTo(LATEST_VERSION);
        assertThat(result).containsEntry("artifactVersionId", FROZEN_VERSION)
                .containsEntry("submissionId", SUBMISSION);
    }

    @Test
    void rejectsArtifactRequestsForAnySubmissionOutsideTheCurrentApprovalRound() {
        when(jdbc.queryForList(contains("FEISHU_PUBLISHED"), eq(PROJECT), eq(SUBMISSION + 1)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.artifacts(REQUIREMENT, SUBMISSION + 1, ACTOR))
                .hasMessageContaining("No pending approval or rejected round is available");

        verify(jdbc, never()).query(contains("p.artifact_version_id=?"), any(RowCallbackHandler.class), any());
    }

    @Test
    void missingFeishuMessageScopeIsReportedAsIncompleteRatherThanAnEmptyCompleteHistory() throws Exception {
        when(feishu.listMessages(eq(CHAT), eq(START.getEpochSecond()), anyLong(), isNull()))
                .thenReturn(FeishuApprovalFeedbackGateway.ApiResult.failure(230027, "missing scope"));
        String cutoff = START.plusSeconds(60).toString();

        Map<String, Object> response = service.messages(REQUIREMENT, SUBMISSION, cutoff, null, ACTOR);

        assertThat(response).containsEntry("messagesComplete", false)
                .containsEntry("threadsComplete", false)
                .containsEntry("items", List.of());
        assertThat((List<?>) response.get("warnings")).anySatisfy(warning ->
                assertThat(warning).isEqualTo("FEISHU_PERMISSION_MISSING_IM_MESSAGE_GROUP_MSG"));
    }

    @Test
    void normalizesMessageBodyContentAndMillisecondTimestampsAndLoadsThreadByThreadId() throws Exception {
        var body = json.readTree("""
                {"code":0,"data":{"has_more":false,"items":[
                  {"message_id":"m-root","thread_id":"thread-abc","root_id":"","create_time":"1792569600000","update_time":"1792569600000","msg_type":"text","body":{"content":"{\\"text\\":\\"Review this paragraph\\"}"},"sender":{"id":"ou-a","sender_type":"user"}}
                ]}}
                """);
        var thread = json.readTree("""
                {"code":0,"data":{"has_more":false,"items":[
                  {"message_id":"m-reply","thread_id":"thread-abc","root_id":"m-root","parent_id":"m-root","create_time":"1792569660000","update_time":"1792569660000","msg_type":"text","body":{"content":"{\\"text\\":\\"I agree\\"}"},"sender":{"id":"ou-b","sender_type":"user"}}
                ]}}
                """);
        when(feishu.listMessages(eq(CHAT), eq(START.getEpochSecond()), anyLong(), isNull()))
                .thenReturn(FeishuApprovalFeedbackGateway.ApiResult.success(body));
        when(feishu.listThread(CHAT, "thread-abc", null))
                .thenReturn(FeishuApprovalFeedbackGateway.ApiResult.success(thread));

        Map<String, Object> response = service.messages(REQUIREMENT, SUBMISSION,
                START.plusSeconds(120).toString(), null, ACTOR);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        assertThat(items).extracting(x -> x.get("messageId")).containsExactly("m-root", "m-reply");
        assertThat(items.get(0)).containsEntry("text", "Review this paragraph")
                .containsEntry("createTime", "1792569600000");
        assertThat(response).containsEntry("messagesComplete", true).containsEntry("threadsComplete", true);
        verify(feishu).listThread(CHAT, "thread-abc", null);
    }

    @Test
    void commentsGatewayReceivesFrozenDocumentTokenButResponseNeverExposesIt() throws Exception {
        capturePublishedIndexDocument("private-doc-token");
        when(feishu.listComments(ACTOR, "private-doc-token", null))
                .thenReturn(FeishuApprovalFeedbackGateway.ApiResult.failure(230027, "missing scope"));

        Map<String, Object> response = service.comments(REQUIREMENT, SUBMISSION,
                START.plusSeconds(60).toString(), null, ACTOR);

        verify(feishu).listComments(ACTOR, "private-doc-token", null);
        assertThat(json.writeValueAsString(response)).doesNotContain("private-doc-token");
        assertThat(response).containsEntry("commentsComplete", false);
    }

    private Map<String, Object> roundRow() {
        return Map.ofEntries(Map.entry("stageId", STAGE), Map.entry("stageKey", "REQUIREMENT"),
                Map.entry("stageTitle", "需求分析"), Map.entry("stageStatus", "PENDING_APPROVAL"),
                Map.entry("currentAttempt", 1), Map.entry("submissionId", SUBMISSION),
                Map.entry("attemptNo", 1), Map.entry("artifactVersionId", FROZEN_VERSION),
                Map.entry("submissionStatus", "PENDING_APPROVAL"), Map.entry("chatId", CHAT),
                Map.entry("approvalStartedAt", Timestamp.from(START)));
    }

    private void capturePublishedIndexDocument(String documentToken) throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler callback = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("title")).thenReturn("需求说明");
            when(rs.getInt("versionNo")).thenReturn(1);
            when(rs.getString("documentToken")).thenReturn(documentToken);
            when(rs.getString("wikiUrl")).thenReturn("https://example.feishu.cn/wiki/node");
            when(rs.getString("nodeToken")).thenReturn("node");
            callback.processRow(rs);
            return null;
        }).when(jdbc).query(contains("p.artifact_version_id=?"), any(RowCallbackHandler.class), eq(FROZEN_VERSION));
        doAnswer(invocation -> null).when(jdbc).query(contains("p.submission_id=?"), any(RowCallbackHandler.class), eq(SUBMISSION));
    }
}
