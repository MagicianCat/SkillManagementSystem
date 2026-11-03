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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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

    @BeforeEach void publishedIndexAvailable() {
        when(packages.findPublishedIndexNode(904L)).thenReturn(java.util.Optional.of("index-node"));
    }

    @Test void newStageFolderIsCreatedBelowPublishedIndex() {
        var manifest=new CollabAsdmPackageRepository.Manifest(71L,900,901,1,902,1,903L,"a".repeat(40),"b".repeat(64),
                "v3.0.1","PUBLISHING_FILES",904L,null,null,77,Instant.now(),null,null);
        when(jdbc.queryForMap(anyString(),eq(900L))).thenReturn(Map.of("wiki_space_id","space","publish_actor_user_id",77L));
        when(attachments.createWikiFolder(77L,"space","index-node","06-后端开发"))
                .thenReturn(new CollabAsdmFeishuAttachmentGateway.WikiFolder("folder-node","folder-obj"));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(901L))).thenReturn(6);
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(901L))).thenReturn("后端开发");

        ReflectionTestUtils.invokeMethod(worker,"ensureStageFolder",manifest);

        verify(packages).saveStageFolder(71L,"folder-node","folder-obj");
    }

    @Test void existingStageAndArchiveAreMovedBelowIndexOnce() {
        var stage=new CollabAsdmPackageRepository.StageHierarchy(901,900,"stage-node","archive-node","index-node");
        when(packages.dueStageHierarchies(20)).thenReturn(List.of(stage));
        when(jdbc.queryForMap(anyString(),eq(900L))).thenReturn(Map.of("wiki_space_id","space","publish_actor_user_id",77L));
        when(attachments.wikiParentNodeToken(77L,"stage-node")).thenReturn("req-root");
        when(attachments.wikiParentNodeToken(77L,"archive-node")).thenReturn("index-node");

        worker.repairStageHierarchy();

        verify(attachments).moveWikiNode(77L,"space","stage-node","index-node");
        verify(attachments,never()).moveWikiNode(eq(77L),eq("space"),eq("archive-node"),anyString());
        verify(packages).markStageHierarchy(stage);
    }

    @Test void reworkedAttachmentArchiveIsCreatedBelowPublishedIndex() {
        var manifest=new CollabAsdmPackageRepository.Manifest(71L,900,901,2,902,1,903L,"a".repeat(40),"b".repeat(64),
                "v3.0.1","PUBLISHING_FILES",904L,"stage-node","stage-obj",77,Instant.now(),null,null);
        var file=new CollabAsdmPackageRepository.FileRecord(12L,71L,"c".repeat(64),"workspace/REQ-1/cases.xlsx",
                "d".repeat(64),"e".repeat(40),4,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "CONFIRMED",100,"[]","DRIVE_FILE",true,"snapshot-key",null,"UPLOADING",null,null,null,null,1);
        when(packages.previousPublishedFile(901,2,"c".repeat(64))).thenReturn(java.util.Optional.of(
                new CollabAsdmPackageRepository.PreviousFile("DRIVE_FILE","x".repeat(64),"old-file","old-node","old-url")));
        when(jdbc.queryForMap(anyString(),eq(900L))).thenReturn(Map.of("wiki_space_id","space","publish_actor_user_id",77L));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(901L))).thenReturn(6);
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(901L))).thenReturn("后端开发");
        when(attachments.createWikiFolder(77L,"space","index-node","06-后端开发-历史产物"))
                .thenReturn(new CollabAsdmFeishuAttachmentGateway.WikiFolder("archive-node","archive-obj"));

        ReflectionTestUtils.invokeMethod(worker,"archivePreviousDriveFile",file,manifest,77L,"space");

        verify(packages).saveStageArchive(901,"archive-node","archive-obj");
        verify(attachments).moveWikiNode(77L,"space","old-node","archive-node");
    }

    @Test void reworkReusesStageFolderAndMarkdownDocument() {
        var manifest=new CollabAsdmPackageRepository.Manifest(71L,900,901,2,902,1,903L,"a".repeat(40),"b".repeat(64),
                "v3.0.1","PUBLISHING_FILES",904L,null,null,77,Instant.now(),null,null);
        when(packages.previousStageFolder(901,2)).thenReturn(java.util.Optional.of(new CollabAsdmPackageRepository.WikiFolder("stage-node","stage-obj")));
        when(jdbc.queryForMap(anyString(),eq(900L))).thenReturn(Map.of("wiki_space_id","space","publish_actor_user_id",77L));
        when(attachments.wikiParentNodeToken(77L,"stage-node")).thenReturn("index-node");
        ReflectionTestUtils.invokeMethod(worker,"ensureStageFolder",manifest);
        verify(packages).saveStageFolder(71L,"stage-node","stage-obj");
        verify(attachments,never()).createWikiFolder(anyLong(),anyString(),anyString(),anyString());

        var file=new CollabAsdmPackageRepository.FileRecord(12L,71L,"c".repeat(64),"workspace/REQ-1/design.md",
                "d".repeat(64),"e".repeat(40),4,"text/markdown","CONFIRMED",100,"[]","DOCX",true,
                "snapshot-key",null,"UPLOADING",null,null,null,null,1);
        when(snapshots.read("snapshot-key",4,"d".repeat(64))).thenReturn("test".getBytes());
        when(packages.previousPublishedFile(901,2,"c".repeat(64))).thenReturn(java.util.Optional.of(
                new CollabAsdmPackageRepository.PreviousFile("DOCX","x".repeat(64),"old-doc","old-node","old-url")));
        when(packages.recordRemoteTarget(12L,"old-doc")).thenReturn(true);
        when(packages.recordMountedNode(12L,"old-node","old-url")).thenReturn(true);
        ReflectionTestUtils.invokeMethod(worker,"publishDocx",file,manifest,77L,"space","stage-node","design.md");
        verify(documents).replaceNativeDocx(77L,"old-doc","test");
        verify(documents,never()).createWikiDocx(anyLong(),anyString(),anyString(),anyString());
    }

    @Test void changedDriveAttachmentIsMountedBeforeOldNodeMovesToArchive() {
        var manifest=new CollabAsdmPackageRepository.Manifest(71L,900,901,2,902,1,903L,"a".repeat(40),"b".repeat(64),
                "v3.0.1","PUBLISHING_FILES",904L,"stage-node","stage-obj",77,Instant.now(),null,null);
        var file=new CollabAsdmPackageRepository.FileRecord(12L,71L,"c".repeat(64),"workspace/REQ-1/cases.xlsx",
                "d".repeat(64),"e".repeat(40),4,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "CONFIRMED",100,"[]","DRIVE_FILE",true,"snapshot-key",null,"UPLOADING",null,null,null,null,1);
        when(packages.previousPublishedFile(901,2,"c".repeat(64))).thenReturn(java.util.Optional.of(
                new CollabAsdmPackageRepository.PreviousFile("DRIVE_FILE","x".repeat(64),"old-file","old-node","old-url")));
        when(packages.stageArchive(901)).thenReturn(java.util.Optional.of(new CollabAsdmPackageRepository.WikiFolder("archive-node","archive-obj")));
        when(snapshots.read("snapshot-key",4,"d".repeat(64))).thenReturn(new byte[]{1,2,3,4});
        when(attachments.rootDriveFolderToken(77L)).thenReturn("root");
        when(attachments.uploadToDrive(eq(77L),eq("root"),eq("cases.xlsx"),anyString(),any())).thenReturn("new-file");
        when(packages.recordRemoteTarget(12L,"new-file")).thenReturn(true);
        when(attachments.mountDriveFile(77L,"space","stage-node","new-file","cases.xlsx"))
                .thenReturn(new CollabAsdmFeishuAttachmentGateway.PublishedAttachment("new-file","new-node","new-url"));
        when(packages.recordMountedNode(12L,"new-node","new-url")).thenReturn(true);
        ReflectionTestUtils.invokeMethod(worker,"publishDrive",file,manifest,77L,"space","stage-node","cases.xlsx");
        var ordered=inOrder(attachments,packages);
        ordered.verify(attachments).mountDriveFile(77L,"space","stage-node","new-file","cases.xlsx");
        ordered.verify(packages).recordMountedNode(12L,"new-node","new-url");
        ordered.verify(attachments).moveWikiNode(77L,"space","old-node","archive-node");
    }

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
