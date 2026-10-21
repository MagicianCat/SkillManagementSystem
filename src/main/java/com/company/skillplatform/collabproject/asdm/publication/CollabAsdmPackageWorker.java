package com.company.skillplatform.collabproject.asdm.publication;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes immutable ASDM package files and releases the stage gate only after the index is updated. */
@Component
public class CollabAsdmPackageWorker {
    private static final Logger log=LoggerFactory.getLogger(CollabAsdmPackageWorker.class);
    private final CollabAsdmPackageRepository packages;
    private final CollabAsdmSnapshotStore snapshots;
    private final CollabAsdmFeishuAttachmentGateway attachments;
    private final FeishuDocumentMcpProxy documents;
    private final CollabProjectFlowService flow;
    private final CollabAsdmProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CollabAsdmPackageWorker(CollabAsdmPackageRepository packages,CollabAsdmSnapshotStore snapshots,
            CollabAsdmFeishuAttachmentGateway attachments,FeishuDocumentMcpProxy documents,
            CollabProjectFlowService flow,CollabAsdmProperties properties,JdbcTemplate jdbc,ObjectMapper json) {
        this.packages=packages;this.snapshots=snapshots;this.attachments=attachments;this.documents=documents;
        this.flow=flow;this.properties=properties;this.jdbc=jdbc;this.json=json;
    }

    @Scheduled(fixedDelayString="${skill-platform.asdm-gitlab.file-worker-delay:10000}")
    public void drain() {
        if(!properties.multiFilePublishEnabled()) return;
        packages.recoverInterruptedUploads();
        for(Long packageId:packages.duePackageIds(properties.filePublishWorkers())) {
            try { process(packageId); }
            catch(Exception ex) { log.warn("event=asdm.package.worker.failed packageId={} errorType={}",packageId,ex.getClass().getSimpleName()); }
        }
    }

    public void process(long packageId) {
        var manifest=packages.findPackage(packageId).orElse(null);
        if(manifest==null) return;
        if(!"PUBLISHED".equals(manifest.packageStatus())) {
            ensureStageFolder(manifest);
            List<Long> due=packages.dueFileIds(packageId,Math.max(1,properties.filePublishWorkers()));
            for(Long fileId:due) publishFile(fileId,manifest);
            var progress=packages.progress(packageId);
            if(progress.reconcileCount()>0) { packages.markPackageStatus(packageId,"RECONCILE_REQUIRED","ASDM_FEISHU_RECONCILE_REQUIRED");return; }
            if(progress.failedCount()>0) { packages.markPackageStatus(packageId,"PARTIAL_FAILED","ASDM_PACKAGE_PARTIAL_FAILED");return; }
            if(!progress.allRequiredPublished()) return;
            packages.setPackageStatus(packageId,manifest.packageStatus(),"PUBLISHING_INDEX",null);
        }
        publishIndexAndRelease(packageId);
    }

    private void ensureStageFolder(CollabAsdmPackageRepository.Manifest manifest) {
        if(manifest.stageWikiNodeToken()!=null&&!manifest.stageWikiNodeToken().isBlank()) return;
        Map<String,Object> target=wikiTarget(manifest.requirementId());
        String title=String.format("%02d-%s-第%d轮",stageOrder(manifest.stageId()),stageTitle(manifest.stageId()),manifest.attemptNo());
        CollabAsdmFeishuAttachmentGateway.WikiFolder folder;
        try { folder=attachments.createWikiFolder(asLong(target,"publish_actor_user_id"),str(target,"wiki_space_id"),
                str(target,"wiki_parent_node_token"),title); }
        catch(RuntimeException ex) { packages.markPackageStatus(manifest.id(),"RECONCILE_REQUIRED","ASDM_FEISHU_RECONCILE_REQUIRED");throw ex; }
        packages.saveStageFolder(manifest.id(),folder.nodeToken(),folder.objectToken());
        if("PUBLISHING_FILES".equals(manifest.packageStatus())) {
            // The package record is reloaded on the next scheduled pass; this pass can use the created target directly.
            stageFolders.put(manifest.id(),folder);
        }
    }

    private final java.util.concurrent.ConcurrentMap<Long,CollabAsdmFeishuAttachmentGateway.WikiFolder> stageFolders=new java.util.concurrent.ConcurrentHashMap<>();

    private void publishFile(long fileId,CollabAsdmPackageRepository.Manifest manifest) {
        if(!packages.claimFile(fileId)) return;
        var file=packages.findFile(fileId).orElse(null);
        if(file==null) return;
        try {
            var target=wikiTarget(manifest.requirementId());
            String wikiNode=manifest.stageWikiNodeToken(); String driveFolder=manifest.stageWikiObjToken();
            var cached=stageFolders.remove(manifest.id());
            if(cached!=null) {wikiNode=cached.nodeToken();driveFolder=cached.objectToken();}
            if(wikiNode==null||wikiNode.isBlank()||driveFolder==null||driveFolder.isBlank()) {
                var reloaded=packages.findPackage(manifest.id()).orElseThrow();
                wikiNode=reloaded.stageWikiNodeToken();driveFolder=reloaded.stageWikiObjToken();
            }
            String name=basename(file.sourcePath());
            PublishedResult result=switch(file.deliveryMode()) {
                case "DOCX" -> publishDocx(file,manifest,asLong(target,"publish_actor_user_id"),str(target,"wiki_space_id"),wikiNode,name);
                case "DRIVE_FILE" -> publishDrive(file,manifest,asLong(target,"publish_actor_user_id"),str(target,"wiki_space_id"),wikiNode,name);
                case "LINK_ONLY" -> new PublishedResult("LINK_ONLY",null,file.sourceGitUrl());
                default -> throw new IllegalArgumentException("ASDM_DELIVERY_MODE_UNSUPPORTED");
            };
            if(!packages.markFileStatus(fileId,"UPLOADING",result.status(),result.targetId(),result.url(),null))
                log.warn("event=asdm.package.file.status_conflict packageId={} fileId={}",manifest.id(),fileId);
        } catch(Exception ex) {
            boolean targetKnown=file.feishuTargetId()!=null&&!file.feishuTargetId().isBlank();
            try { targetKnown=targetKnown||packages.findFile(fileId).map(x->x.feishuTargetId()!=null&&!x.feishuTargetId().isBlank()).orElse(false); }
            catch(Exception ignored) { /* Preserve the conservative unknown-remote-result path. */ }
            String message=ex.getMessage()==null?"":ex.getMessage();
            if(message.contains("code=99991679")) {
                packages.markFileStatus(fileId,"UPLOADING","FAILED",null,null,"ASDM_FEISHU_PERMISSION_REQUIRED");
                return;
            }
            boolean knownRejection=message.startsWith("Feishu API rejected request")||message.startsWith("ASDM_FILE_");
            boolean uncertain=message.contains("REMOTE_TARGET_PERSISTENCE")||!targetKnown&&!knownRejection;
            String error=ex.getMessage()==null?"ASDM_FEISHU_PUBLISH_FAILED":ex.getMessage().substring(0,Math.min(80,ex.getMessage().length()));
            if(uncertain) packages.markFileStatus(fileId,"UPLOADING","RECONCILE_REQUIRED",null,null,error);
            else if(file.attempts()<properties.filePublishRetryLimit()) packages.scheduleRetry(fileId,error,Math.min(3600,15L<<Math.min(7,file.attempts())));
            else packages.markFileStatus(fileId,"UPLOADING","FAILED",null,null,error);
        }
    }

    private PublishedResult publishDocx(CollabAsdmPackageRepository.FileRecord f,CollabAsdmPackageRepository.Manifest m,
            long actor,String space,String parent,String title) {
        byte[] bytes=snapshots.read(f.snapshotStorageKey(),f.sourceSizeBytes(),f.sourceFileSha256());
        try {
            String markdown=new String(bytes,StandardCharsets.UTF_8);
            if(f.feishuTargetId()!=null&&!f.feishuTargetId().isBlank()) {
                documents.replaceNativeDocx(actor,f.feishuTargetId(),markdown);
                return new PublishedResult("PUBLISHED",f.feishuTargetId(),f.feishuUrl());
            }
            var created=documents.createWikiDocx(actor,space,parent,title);
            String docToken=created.path("documentToken").asText("");
            if(docToken.isBlank())throw new IllegalStateException("ASDM_FEISHU_DOCX_TOKEN_MISSING");
            if(!packages.recordRemoteTarget(f.id(),docToken))throw new IllegalStateException("ASDM_FEISHU_REMOTE_TARGET_PERSISTENCE_FAILED");
            String nodeToken=created.path("nodeToken").asText(docToken),url=created.path("documentUrl").asText("");
            if(!packages.recordMountedNode(f.id(),nodeToken,url))throw new IllegalStateException("ASDM_FEISHU_REMOTE_TARGET_PERSISTENCE_FAILED");
            documents.writeNativeDocx(actor,docToken,markdown);
            return new PublishedResult("PUBLISHED",docToken,url);
        } finally {java.util.Arrays.fill(bytes,(byte)0);}
    }

    private PublishedResult publishDrive(CollabAsdmPackageRepository.FileRecord f,CollabAsdmPackageRepository.Manifest m,
            long actor,String space,String wikiNode,String fileName) {
        if(f.feishuNodeToken()!=null&&!f.feishuNodeToken().isBlank())
            return new PublishedResult("PUBLISHED",f.feishuTargetId(),f.feishuUrl());
        byte[] bytes=snapshots.read(f.snapshotStorageKey(),f.sourceSizeBytes(),f.sourceFileSha256());
        try {
            String fileToken=f.feishuTargetId();
            if(fileToken==null||fileToken.isBlank()) {
                fileToken=attachments.uploadToDrive(actor,attachments.rootDriveFolderToken(actor),fileName,f.mediaType(),bytes);
                if(!packages.recordRemoteTarget(f.id(),fileToken))throw new IllegalStateException("ASDM_FEISHU_REMOTE_TARGET_PERSISTENCE_FAILED");
            }
            var result=attachments.mountDriveFile(actor,space,wikiNode,fileToken,fileName);
            if(!packages.recordMountedNode(f.id(),result.nodeToken(),result.url()))
                throw new IllegalStateException("ASDM_FEISHU_REMOTE_TARGET_PERSISTENCE_FAILED");
            return new PublishedResult("PUBLISHED",fileToken,result.url());
        } finally {java.util.Arrays.fill(bytes,(byte)0);}
    }

    private void publishIndexAndRelease(long packageId) {
        var manifest=packages.findPackage(packageId).orElseThrow();
        if(!"PUBLISHING_INDEX".equals(manifest.packageStatus())) return;
        var index=jdbc.query("SELECT pub.publish_status,pub.document_token,pub.node_token,pub.url,v.markdown_content,a.title,s.title stage_title,s.stage_order,p.owner_user_id FROM collab_feishu_publication pub JOIN collab_artifact_version v ON v.id=pub.artifact_version_id JOIN collab_artifact a ON a.id=v.artifact_id JOIN collab_stage s ON s.id=v.stage_id JOIN collab_project p ON p.id=s.project_id WHERE pub.artifact_version_id=?",
                rs->rs.next()?Map.of("publish_status",safe(rs.getString("publish_status")),"document_token",safe(rs.getString("document_token")),"node_token",safe(rs.getString("node_token")),"url",safe(rs.getString("url")),"markdown_content",safe(rs.getString("markdown_content")),"title",safe(rs.getString("title")),"stage_title",safe(rs.getString("stage_title")),"stage_order",rs.getInt("stage_order"),"owner_user_id",rs.getLong("owner_user_id")):null,
                manifest.sourceIndexArtifactVersionId());
        if(index==null||!"PUBLISHED".equals(index.get("publish_status"))) return;
        var progress=packages.progress(packageId);
        if(!progress.allRequiredPublished()) return;
        Long actor=publishActor(manifest.requirementId());
        List<CollabAsdmPackageRepository.FileRecord> files=packages.findFiles(packageId);
        StringBuilder content=new StringBuilder(String.valueOf(index.get("markdown_content"))).append("\n\n## 飞书发布文件\n\n| 来源路径 | 状态 | 飞书链接 |\n|---|---|---|\n");
        for(var file:files) {
            String url=file.feishuUrl()==null?file.sourceGitUrl():file.feishuUrl();
            content.append("| `").append(file.sourcePath().replace("|","\\|")).append("` | ")
                    .append(file.publishStatus()).append(" | ").append(url==null||url.isBlank()?"—":"[打开]("+url+")").append(" |\n");
        }
        documents.replaceNativeDocx(actor,String.valueOf(index.get("document_token")),content.toString());
        flow.onAsdmPackagePublished(packageId);
        for(var file:files) {
            try { snapshots.delete(file.snapshotStorageKey()); }
            catch(RuntimeException ex) { log.warn("event=asdm.snapshot.cleanup_failed packageId={} fileId={}",packageId,file.id()); }
        }
    }

    private Map<String,Object> wikiTarget(long requirementId) {
        return jdbc.queryForMap("SELECT b.wiki_space_id,b.wiki_parent_node_token,b.publish_actor_user_id FROM collab_feishu_binding b WHERE b.project_id=?",requirementId);
    }
    private Long publishActor(long requirementId){return jdbc.queryForObject("SELECT publish_actor_user_id FROM collab_feishu_binding WHERE project_id=?",Long.class,requirementId);}
    private int stageOrder(long stageId){return jdbc.queryForObject("SELECT stage_order FROM collab_stage WHERE id=?",Integer.class,stageId);}
    private String stageTitle(long stageId){return jdbc.queryForObject("SELECT title FROM collab_stage WHERE id=?",String.class,stageId);}
    private static long asLong(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    private static String str(Map<String,Object> m,String key){Object x=m.get(key);if(x==null||x.toString().isBlank())throw new IllegalStateException("COLLAB_WIKI_ROOT_REQUIRED");return x.toString();}
    private static String basename(String path){int i=path.lastIndexOf('/');return i<0?path:path.substring(i+1);}
    private static String safe(String s){return s==null?"":s;}
    public record PublishedResult(String status,String targetId,String url){}
}
