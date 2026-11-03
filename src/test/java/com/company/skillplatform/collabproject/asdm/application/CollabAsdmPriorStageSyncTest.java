package com.company.skillplatform.collabproject.asdm.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.collabproject.application.CollabFeishuChatGateway;
import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmFeishuAttachmentGateway;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmSnapshotStore;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.ResultSet;

class CollabAsdmPriorStageSyncTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final CollabAsdmRuleService rules=mock(CollabAsdmRuleService.class);
    private final CollabAsdmConfigurationService configuration=mock(CollabAsdmConfigurationService.class);
    private final CollabAsdmGitlabClient gitlab=mock(CollabAsdmGitlabClient.class);
    private final CollabFeishuChatGateway chats=mock(CollabFeishuChatGateway.class);
    private final CollabAsdmProperties properties=new CollabAsdmProperties(true,List.of("gitlab.example"),"",
            Duration.ofSeconds(60),Duration.ofSeconds(1),Duration.ofSeconds(5),Duration.ofSeconds(20),1_048_576,5000,20,
            true,true,20000,300,20L*1024*1024,200L*1024*1024,false,1,2,Duration.ofHours(24));
    private final CollabAsdmSyncService service=new CollabAsdmSyncService(jdbc,mock(CollabProjectAuthorizationService.class),rules,
            mock(CollabAsdmStageRules.class),configuration,gitlab,properties,mock(CollabAsdmMultiStageDetectionService.class),
            mock(CollabAsdmSnapshotStore.class),mock(CollabAsdmFeishuAttachmentGateway.class),mock(FeishuDocumentMcpProxy.class),chats);

    @Test void automaticScanUsesClickedCommitAndOnlyEarlierCompletedStages() {
        String head="a".repeat(40);
        when(jdbc.update(startsWith("update collab_asdm_sync_job set status='SCANNING'"),eq(17L))).thenReturn(1);
        when(jdbc.queryForMap(startsWith("select trigger_kind"),eq(17L)))
                .thenReturn(Map.of("trigger_kind","AUTO_PRIOR","source_stage_order",6,"head_sha",head));
        when(jdbc.queryForMap(startsWith("select j.requirement_id"),eq(17L)))
                .thenReturn(Map.of("requirement_id",9L,"group_id",3L,"name","测试需求","asdm_branch","feature/test","asdm_requirement_path","workspace/requirements/REQ-1"));
        var access=new CollabAsdmConfigurationService.RepositoryAccess(1L,"https://gitlab.example/group/repo",
                URI.create("https://gitlab.example"),"test-token",1);
        when(configuration.requireAccess(3L)).thenReturn(access);
        when(gitlab.listTreeRecursive(eq(1L),eq(head),eq("workspace"),eq("test-token"),anyInt(),any())).thenReturn(List.of());
        when(rules.effectiveForRequirement(9L,false)).thenReturn(new CollabAsdmRuleService.EffectiveRules(1,"v3","{}",Map.of(),"b".repeat(64)));
        when(jdbc.queryForList(contains("stage_order<? and status='COMPLETED'"),eq(9L),eq(6))).thenReturn(List.of());
        when(jdbc.queryForObject(startsWith("select count(*) from collab_asdm_sync_file"),eq(Integer.class),eq(17L))).thenReturn(0);

        ReflectionTestUtils.invokeMethod(service,"scan",17L);

        verify(gitlab,never()).getBranch(anyLong(),anyString(),anyString(),any());
        verify(jdbc).queryForList(contains("stage_order<? and status='COMPLETED'"),eq(9L),eq(6));
        verify(jdbc).update(startsWith("update collab_asdm_sync_job set status=?"),eq("COMPLETED"),eq(17L));
    }

    @Test void summaryCardContainsResultsAndNoMentions() {
        when(jdbc.queryForMap(startsWith("select j.status"),eq(17L))).thenReturn(Map.of(
                "status","COMPLETED","head_sha","a".repeat(40),"requirement_name","测试需求",
                "group_name","测试项目组","source_stage_title","后端开发","chat_id","chat-1"));
        when(jdbc.queryForList(startsWith("select s.title"),eq(17L))).thenReturn(List.of(Map.of(
                "title","产品设计","status","COMPLETED","new_count",0,"updated_count",1,
                "supplemental_index_url","https://example.feishu.cn/wiki/abc")));

        ReflectionTestUtils.invokeMethod(service,"sendSummary",17L);

        verify(chats).sendInteractive(eq("chat-1"),argThat(card -> {
            String rendered=card.toString();
            return rendered.contains("产品设计") && rendered.contains("更新 1")
                    && rendered.contains("https://example.feishu.cn/wiki/abc") && !rendered.contains("<at");
        }),eq("asdm-prior-sync:17:COMPLETED"));
    }

    @Test void completedStageUsesOriginalPublishedDocumentDespiteEarlierSupplementalCopy() throws Exception {
        ResultSet row=mock(ResultSet.class);
        when(row.next()).thenReturn(true);
        when(row.getString(1)).thenReturn("DOCX");
        when(row.getString(2)).thenReturn("original-token");
        when(row.getString(3)).thenReturn("original-node");
        when(row.getString(4)).thenReturn("https://feishu.example/original");
        doAnswer(invocation -> ((ResultSetExtractor<?>)invocation.getArgument(1)).extractData(row))
                .when(jdbc).query(startsWith("select f.delivery_mode,f.feishu_target_id"),any(ResultSetExtractor.class),eq(10L),eq("path-hash"));

        Object target=ReflectionTestUtils.invokeMethod(service,"previousTarget",10L,"path-hash");

        org.junit.jupiter.api.Assertions.assertTrue(target.toString().contains("original-token"));
        verify(jdbc,never()).query(contains("join collab_asdm_sync_file f"),any(ResultSetExtractor.class),anyLong(),anyString());
    }

    @Test void replacedAttachmentMovesOldNodeToHistoryOnlyOnce() throws Exception {
        ResultSet row=mock(ResultSet.class);
        when(row.next()).thenReturn(true);
        when(row.getString(1)).thenReturn("history-node");
        doAnswer(invocation -> ((ResultSetExtractor<?>)invocation.getArgument(1)).extractData(row))
                .when(jdbc).query(startsWith("select wiki_node_token from collab_asdm_stage_archive"),any(ResultSetExtractor.class),eq(10L));
        CollabAsdmFeishuAttachmentGateway gateway=(CollabAsdmFeishuAttachmentGateway)ReflectionTestUtils.getField(service,"attachments");
        when(gateway.wikiParentNodeToken(3L,"old-node")).thenReturn("stage-node","history-node");

        ReflectionTestUtils.invokeMethod(service,"archivePreviousAttachment",3L,"space",10L,"old-node");
        ReflectionTestUtils.invokeMethod(service,"archivePreviousAttachment",3L,"space",10L,"old-node");

        verify(gateway,times(1)).moveWikiNode(3L,"space","old-node","history-node");
    }
}
