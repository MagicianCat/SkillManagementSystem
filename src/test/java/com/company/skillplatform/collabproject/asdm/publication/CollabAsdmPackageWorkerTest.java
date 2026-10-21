package com.company.skillplatform.collabproject.asdm.publication;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CollabAsdmPackageWorkerTest {
    private final CollabAsdmPackageRepository packages=mock(CollabAsdmPackageRepository.class);
    private final CollabAsdmSnapshotStore snapshots=mock(CollabAsdmSnapshotStore.class);
    private final CollabAsdmFeishuAttachmentGateway attachments=mock(CollabAsdmFeishuAttachmentGateway.class);
    private final FeishuDocumentMcpProxy documents=mock(FeishuDocumentMcpProxy.class);
    private final CollabProjectFlowService flow=mock(CollabProjectFlowService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final CollabAsdmProperties props=new CollabAsdmProperties(true,List.of("gitlab.example"),"",
            Duration.ofSeconds(60),Duration.ofSeconds(300),Duration.ofSeconds(5),Duration.ofSeconds(20),1_048_576,5000,20,
            true,true,20000,300,20L*1024*1024,200L*1024*1024,false,1,2,Duration.ofHours(24));
    private final CollabAsdmPackageWorker worker=new CollabAsdmPackageWorker(packages,snapshots,attachments,documents,flow,props,jdbc,new ObjectMapper());

    @Test void deniedAttachmentUploadLeavesPackageBlockedAndDoesNotReleaseStage() {
        long packageId=71;
        var manifest=new CollabAsdmPackageRepository.Manifest(packageId,900,901,1,902,1,903L,"a".repeat(40),"b".repeat(64),
                "v3.0.1","PUBLISHING_FILES",904L,"wiki-stage-node","drive-stage-folder",77,Instant.now(),null,null);
        var file=new CollabAsdmPackageRepository.FileRecord(12L,packageId,"c".repeat(64),"workspace/REQ-1/cases.xlsx",
                "d".repeat(64),"e".repeat(40),5,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","CONFIRMED",100,
                "[]","DRIVE_FILE",true,"snapshot-key",null,"UPLOADING",null,null,null,null,2);
        when(packages.findPackage(packageId)).thenReturn(java.util.Optional.of(manifest));
        when(packages.dueFileIds(packageId,1)).thenReturn(List.of(12L));
        when(packages.claimFile(12L)).thenReturn(true);
        when(packages.findFile(12L)).thenReturn(java.util.Optional.of(file));
        when(jdbc.queryForMap(anyString(),eq(900L))).thenReturn(Map.of("wiki_space_id","space","wiki_parent_node_token","req-root","publish_actor_user_id",77L));
        when(snapshots.read("snapshot-key",5,"d".repeat(64))).thenReturn(new byte[]{1,2,3,4,5});
        when(packages.progress(packageId)).thenReturn(new CollabAsdmPackageRepository.PackageProgress(1,0,1,0,false));
        when(attachments.rootDriveFolderToken(77L)).thenReturn("drive-root");
        when(attachments.uploadToDrive(eq(77L),eq("drive-root"),eq("cases.xlsx"),anyString(),any()))
                .thenThrow(new IllegalStateException("Feishu API rejected request: code=403"));

        worker.process(packageId);

        verify(packages).markFileStatus(eq(12L),eq("UPLOADING"),eq("FAILED"),eq(null),eq(null),contains("403"));
        verify(packages).markPackageStatus(packageId,"PARTIAL_FAILED","ASDM_PACKAGE_PARTIAL_FAILED");
        verify(flow,never()).onAsdmPackagePublished(anyLong());
        verify(documents,never()).replaceNativeDocx(anyLong(),anyString(),anyString());
    }

    @Test void packageWithUnpublishedRequiredFileCannotReleaseStage() {
        long packageId=81;
        var manifest=new CollabAsdmPackageRepository.Manifest(packageId,900,901,1,902,1,903L,"a".repeat(40),"b".repeat(64),
                "v3.0.1","PUBLISHING_INDEX",904L,"wiki-stage-node","drive-stage-folder",77,Instant.now(),null,null);
        when(packages.findPackage(packageId)).thenReturn(java.util.Optional.of(manifest));
        when(packages.dueFileIds(packageId,1)).thenReturn(List.of());
        when(packages.progress(packageId)).thenReturn(new CollabAsdmPackageRepository.PackageProgress(2,1,0,0,false));

        worker.process(packageId);

        verify(flow,never()).onAsdmPackagePublished(anyLong());
        verifyNoInteractions(jdbc);
    }

    @Test void wikiMoveAuthorizationFailureIsImmediatelyRetryableAfterPermissionGrant() {
        long packageId=91;
        var manifest=new CollabAsdmPackageRepository.Manifest(packageId,900,901,1,902,1,903L,"a".repeat(40),"b".repeat(64),
                "v3.0.1","PUBLISHING_FILES",904L,"wiki-stage-node","wiki-stage-docx",77,Instant.now(),null,null);
        var file=new CollabAsdmPackageRepository.FileRecord(13L,packageId,"c".repeat(64),"workspace/REQ-1/cases.xlsx",
                "d".repeat(64),"e".repeat(40),5,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","CONFIRMED",100,
                "[]","DRIVE_FILE",true,"snapshot-key",null,"UPLOADING","drive-file-token",null,null,null,1);
        when(packages.findPackage(packageId)).thenReturn(java.util.Optional.of(manifest));
        when(packages.dueFileIds(packageId,1)).thenReturn(List.of(13L));
        when(packages.claimFile(13L)).thenReturn(true);
        when(packages.findFile(13L)).thenReturn(java.util.Optional.of(file));
        when(jdbc.queryForMap(anyString(),eq(900L))).thenReturn(Map.of("wiki_space_id","space","wiki_parent_node_token","req-root","publish_actor_user_id",77L));
        when(snapshots.read("snapshot-key",5,"d".repeat(64))).thenReturn(new byte[]{1,2,3,4,5});
        when(packages.progress(packageId)).thenReturn(new CollabAsdmPackageRepository.PackageProgress(1,0,1,0,false));
        when(attachments.mountDriveFile(77L,"space","wiki-stage-node","drive-file-token","cases.xlsx"))
                .thenThrow(new IllegalStateException("Feishu API rejected request: code=99991679,msg=Unauthorized"));

        worker.process(packageId);

        verify(packages).markFileStatus(13L,"UPLOADING","FAILED",null,null,"ASDM_FEISHU_PERMISSION_REQUIRED");
        verify(packages).markPackageStatus(packageId,"PARTIAL_FAILED","ASDM_PACKAGE_PARTIAL_FAILED");
        verify(packages,never()).scheduleRetry(anyLong(),anyString(),anyLong());
        verify(flow,never()).onAsdmPackagePublished(anyLong());
    }
}
