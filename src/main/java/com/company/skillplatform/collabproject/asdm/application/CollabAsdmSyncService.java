package com.company.skillplatform.collabproject.asdm.application;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmFeishuAttachmentGateway;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmSnapshotStore;
import com.company.skillplatform.common.application.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit rule synchronization. Supplemental publication is isolated from stage submissions. */
@Service
public class CollabAsdmSyncService {
    private static final Logger log = LoggerFactory.getLogger(CollabAsdmSyncService.class);
    private static final Set<String> ACTIVE = Set.of("IN_PROGRESS", "REWORK");
    private final JdbcTemplate jdbc;
    private final CollabProjectAuthorizationService authorization;
    private final CollabAsdmRuleService ruleService;
    private final CollabAsdmStageRules stageRules;
    private final CollabAsdmConfigurationService configuration;
    private final CollabAsdmGitlabClient gitlab;
    private final CollabAsdmProperties properties;
    private final CollabAsdmMultiStageDetectionService detection;
    private final CollabAsdmSnapshotStore snapshots;
    private final CollabAsdmFeishuAttachmentGateway attachments;
    private final FeishuDocumentMcpProxy documents;

    public CollabAsdmSyncService(JdbcTemplate jdbc, CollabProjectAuthorizationService authorization,
            CollabAsdmRuleService ruleService, CollabAsdmStageRules stageRules,
            CollabAsdmConfigurationService configuration, CollabAsdmGitlabClient gitlab,
            CollabAsdmProperties properties, CollabAsdmMultiStageDetectionService detection,
            CollabAsdmSnapshotStore snapshots, CollabAsdmFeishuAttachmentGateway attachments,
            FeishuDocumentMcpProxy documents) {
        this.jdbc=jdbc; this.authorization=authorization; this.ruleService=ruleService; this.stageRules=stageRules;
        this.configuration=configuration; this.gitlab=gitlab; this.properties=properties; this.detection=detection;
        this.snapshots=snapshots; this.attachments=attachments; this.documents=documents;
    }

    @Transactional
    public SyncAccepted start(String requirementKey, long actorId, String idempotencyKey, SyncRequest request) {
        if(!properties.enabled()||!properties.multiStageEnabled()||!properties.multiFilePublishEnabled())
            throw new BusinessException("ASDM_SYNC_DISABLED","ASDM multi-stage publication is disabled",HttpStatus.SERVICE_UNAVAILABLE);
        var requirement = authorization.requireRequirementManager(requirementKey, actorId);
        authorization.requireWritable(requirement);
        if (request == null || !request.applyLatestGroupRules() || !"ALL_STAGES".equals(request.scope()) || !request.includeCompletedStages())
            throw bad("ASDM_SYNC_SCOPE_INVALID");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length()>128)
            throw bad("ASDM_SYNC_IDEMPOTENCY_KEY_REQUIRED");
        jdbc.queryForObject("select id from collab_project where id=? for update",Long.class,requirement.projectId());
        var binding = jdbc.query("select asdm_branch,asdm_requirement_path from collab_project where id=?",
                rs -> rs.next() ? new Binding(rs.getString(1),rs.getString(2)) : null, requirement.projectId());
        if (binding == null || binding.branch()==null || binding.path()==null) throw conflict("ASDM_REQUIREMENT_BINDING_REQUIRED");
        configuration.requireAccess(requirement.groupId());
        List<SyncAccepted> existing=jdbc.query("select id,status from collab_asdm_sync_job where requirement_id=? and idempotency_key=?",
                (rs,n)->new SyncAccepted(rs.getLong(1),rs.getString(2),"重复请求已受理"),requirement.projectId(),idempotencyKey);
        if (!existing.isEmpty()) return existing.get(0);
        Integer running=jdbc.queryForObject("select count(*) from collab_asdm_sync_job where requirement_id=? and status in ('QUEUED','SCANNING','PUBLISHING')",
                Integer.class,requirement.projectId());
        if (running!=null && running>0) throw conflict("ASDM_SYNC_ALREADY_RUNNING");
        var rules=ruleService.bindLatestForRequirement(requirement.projectId(), actorId);
        jdbc.update("insert into collab_asdm_sync_job(requirement_id,requested_by,idempotency_key,group_revision,default_version,rule_hash,status) values(?,?,?,?,?,?,'QUEUED')",
                requirement.projectId(),actorId,idempotencyKey,rules.groupRevision(),rules.defaultVersion(),rules.effectiveHash());
        Long id=jdbc.queryForObject("select id from collab_asdm_sync_job where requirement_id=? and idempotency_key=?",Long.class,requirement.projectId(),idempotencyKey);
        return new SyncAccepted(id,"QUEUED","已受理；进行中阶段重新检测，已完成阶段执行补充归档");
    }

    public JobView job(String requirementKey, long actorId, long jobId) {
        var requirement=authorization.requireMemberOrThrow(requirementKey,actorId);
        var row=jdbc.query("select id,requirement_id,group_revision,default_version,rule_hash,head_sha,status,last_error_code from collab_asdm_sync_job where id=? and requirement_id=?",
                rs->rs.next()?new JobRow(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)):null,
                jobId,requirement.projectId());
        if(row==null)throw new BusinessException("ASDM_SYNC_JOB_NOT_FOUND","Sync job not found",HttpStatus.NOT_FOUND);
        List<StageView> stages=jdbc.query("select ss.id,ss.stage_key,ss.stage_status_snapshot,ss.action,ss.status,ss.found_count,ss.new_count,ss.updated_count,ss.uncertain_count,ss.supplemental_index_url,ss.last_error_code,c.id candidate_id,c.status candidate_status,c.notify_after from collab_asdm_sync_stage ss left join collab_asdm_candidate c on c.id=(select max(c2.id) from collab_asdm_candidate c2 where c2.stage_id=ss.stage_id) where ss.job_id=? order by ss.id",
                (rs,n)->new StageView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getInt(9),rs.getString(10),rs.getString(11),
                        rs.getObject("candidate_id")==null?null:rs.getLong("candidate_id"),rs.getString("candidate_status"),rs.getTimestamp("notify_after")==null?null:rs.getTimestamp("notify_after").toInstant(),files(rs.getLong(1))),jobId);
        return new JobView(row.id(),row.status(),row.headSha(),row.ruleHash(),row.defaultVersion()+"+g"+row.groupRevision(),row.groupRevision(),row.error(),stages);
    }

    @Transactional
    public JobView retry(String requirementKey,long actorId,long jobId) {
        authorization.requireRequirementManager(requirementKey,actorId);
        JobView old=job(requirementKey,actorId,jobId);
        if (!Set.of("PARTIAL_FAILED","FAILED").contains(old.status())) throw conflict("ASDM_SYNC_NOT_RETRYABLE");
        if("FAILED".equals(old.status())) {
            jdbc.update("delete f from collab_asdm_sync_file f join collab_asdm_sync_stage s on s.id=f.sync_stage_id where s.job_id=?",jobId);
            jdbc.update("delete from collab_asdm_sync_stage where job_id=?",jobId);
            jdbc.update("update collab_asdm_sync_job set status='QUEUED',last_error_code=NULL,head_sha=NULL,time_updated=now(3) where id=?",jobId);
            return job(requirementKey,actorId,jobId);
        }
        Integer unreconciled=jdbc.queryForObject("select (select count(*) from collab_asdm_sync_stage where job_id=? and last_error_code like '%RECONCILE_REQUIRED%')+(select count(*) from collab_asdm_sync_file f join collab_asdm_sync_stage s on s.id=f.sync_stage_id where s.job_id=? and f.publish_status='RECONCILE_REQUIRED')",Integer.class,jobId,jobId);
        if(unreconciled!=null&&unreconciled>0)throw conflict("ASDM_FEISHU_RECONCILE_REQUIRED");
        jdbc.update("update collab_asdm_sync_file f join collab_asdm_sync_stage s on s.id=f.sync_stage_id set f.publish_status='PENDING',f.last_error_code=NULL,f.time_updated=now(3) where s.job_id=? and f.publish_status='FAILED'",jobId);
        jdbc.update("update collab_asdm_sync_stage set status='PENDING',last_error_code=NULL,time_updated=now(3) where job_id=? and status='FAILED'",jobId);
        jdbc.update("update collab_asdm_sync_job set status='PUBLISHING',last_error_code=NULL,time_updated=now(3) where id=?",jobId);
        return job(requirementKey,actorId,jobId);
    }

    @Scheduled(fixedDelayString="${skill-platform.asdm-gitlab.sync-worker-delay:10000}")
    public void drain() {
        if (!properties.enabled()||!properties.multiStageEnabled()||!properties.multiFilePublishEnabled()) return;
        jdbc.update("update collab_asdm_sync_job set status='FAILED',last_error_code='ASDM_SYNC_INTERRUPTED',time_updated=now(3) where status='SCANNING' and time_updated<date_sub(now(3),interval 10 minute)");
        jdbc.update("update collab_asdm_sync_file f join collab_asdm_sync_stage s on s.id=f.sync_stage_id join collab_asdm_sync_job j on j.id=s.job_id set f.publish_status='RECONCILE_REQUIRED',f.last_error_code='ASDM_FEISHU_RECONCILE_REQUIRED' where j.status='PROCESSING' and j.time_updated<date_sub(now(3),interval 10 minute) and f.publish_status='UPLOADING'");
        jdbc.update("update collab_asdm_sync_stage s join collab_asdm_sync_job j on j.id=s.job_id set s.status='FAILED',s.last_error_code='ASDM_FEISHU_RECONCILE_REQUIRED' where j.status='PROCESSING' and j.time_updated<date_sub(now(3),interval 10 minute) and s.status='PUBLISHING'");
        jdbc.update("update collab_asdm_sync_job set status='PARTIAL_FAILED',last_error_code='ASDM_SYNC_INTERRUPTED',time_updated=now(3) where status='PROCESSING' and time_updated<date_sub(now(3),interval 10 minute)");
        List<Long> queued=jdbc.query("select id from collab_asdm_sync_job where status='QUEUED' order by id limit 2",(rs,n)->rs.getLong(1));
        for(Long id:queued)try { scan(id); }catch(Exception ex){ failJob(id,ex); }
        List<Long> publishing=jdbc.query("select id from collab_asdm_sync_job where status='PUBLISHING' order by id limit 2",(rs,n)->rs.getLong(1));
        for(Long id:publishing)try {
            if(jdbc.update("update collab_asdm_sync_job set status='PROCESSING',time_updated=now(3) where id=? and status='PUBLISHING'",id)==1)publish(id);
        }catch(Exception ex){ failJob(id,ex); }
    }

    private void scan(long jobId) {
        if (jdbc.update("update collab_asdm_sync_job set status='SCANNING',time_updated=now(3) where id=? and status='QUEUED'",jobId)!=1)return;
        Map<String,Object> request=jdbc.queryForMap("select j.requirement_id,p.group_id,p.name,p.asdm_branch,p.asdm_requirement_path,g.asdm_repository_url from collab_asdm_sync_job j join collab_project p on p.id=j.requirement_id join collab_group g on g.id=p.group_id where j.id=?",jobId);
        long requirementId=num(request,"requirement_id"); long groupId=num(request,"group_id");
        String branch=str(request,"asdm_branch"), reqPath=str(request,"asdm_requirement_path"), name=str(request,"name");
        var access=configuration.requireAccess(groupId);
        String head=gitlab.getBranch(access.projectId(),branch,access.token(),access.repositoryBase()).headSha();
        List<CollabAsdmGitlabClient.TreeEntry> tree=gitlab.listTreeRecursive(access.projectId(),head,"workspace",access.token(),properties.maxDiscoveryEntries(),access.repositoryBase());
        var rules=ruleService.effectiveForRequirement(requirementId,false).rules();
        Map<String,CollabAsdmGitlabClient.FileContent> rawByPath=new LinkedHashMap<>();
        long loadedBytes=0;
        jdbc.update("update collab_asdm_sync_job set head_sha=?,time_updated=now(3) where id=?",head,jobId);
        List<Map<String,Object>> stages=jdbc.queryForList("select id,stage_key,status,attempt_no from collab_stage where project_id=? order by stage_order",requirementId);
        for(Map<String,Object> stage:stages) {
            String key=str(stage,"stage_key"), status=str(stage,"status"); long stageId=num(stage,"id");
            String action= !rules.containsKey(key)||!rules.get(key).enabled()||"SKIPPED".equals(status)?"SKIPPED":
                    ACTIVE.contains(status)?"ACTIVE_DISCOVERY":"COMPLETED".equals(status)?"BACKFILL":"PREVIEW";
            jdbc.update("insert into collab_asdm_sync_stage(job_id,stage_id,stage_key,stage_status_snapshot,action,status) values(?,?,?,?,?,'PENDING')",
                    jobId,stageId,key,status,action);
            long syncStageId=jdbc.queryForObject("select id from collab_asdm_sync_stage where job_id=? and stage_id=?",Long.class,jobId,stageId);
            if("SKIPPED".equals(action)){stageStatus(syncStageId,"SKIPPED",null);continue;}
            var config=rules.get(key);
            List<CollabAsdmStageRules.InputFile> input=new ArrayList<>();
            for(var entry:tree) {
                if(!"blob".equals(entry.type())||!stageRules.mayMatchStagePath(key,reqPath,entry.path(),config))continue;
                if(input.size()>=properties.maxCandidateFiles())throw new IllegalStateException("ASDM_CANDIDATE_LIMIT_EXCEEDED");
                var blob=rawByPath.get(entry.path());
                if(blob==null) {
                    blob=gitlab.getFileBytes(access.projectId(),entry.path(),head,access.token(),access.repositoryBase(),properties.maxFileBytes());
                    loadedBytes+=blob.size();
                    if(loadedBytes>properties.maxPackageBytes())throw new IllegalStateException("ASDM_SYNC_TOTAL_BYTES_EXCEEDED");
                    rawByPath.put(entry.path(),blob);
                }
                String text=entry.path().toLowerCase(Locale.ROOT).endsWith(".md")?new String(blob.content(),StandardCharsets.UTF_8):null;
                input.add(new CollabAsdmStageRules.InputFile(entry.path(),blob.blobSha(),blob.size(),text,true));
            }
            var evaluated=stageRules.evaluate(key,reqPath,name,input,properties.matchLikelyAutoinclude(),Map.of(),config);
            int uncertain=(int)evaluated.files().stream().filter(f->"UNCERTAIN".equals(f.association())).count();
            jdbc.update("update collab_asdm_sync_stage set found_count=?,uncertain_count=?,time_updated=now(3) where id=?",evaluated.files().size(),uncertain,syncStageId);
            if("BACKFILL".equals(action)) prepareBackfill(syncStageId,requirementId,stageId,head,access,evaluated,rawByPath);
            else {
                persistPreview(syncStageId,requirementId,stageId,head,access,evaluated,rawByPath);
                stageStatus(syncStageId,"ACTIVE_DISCOVERY".equals(action)?"CANDIDATE_PENDING":"PREVIEWED",null);
            }
        }
        // The existing detector preserves its debounce, Feishu card and submit gate.
        if(stages.stream().anyMatch(s->ACTIVE.contains(str(s,"status"))))detection.scanRequirementAtHead(requirementId,head,tree,rawByPath);
        Integer pending=jdbc.queryForObject("select count(*) from collab_asdm_sync_file f join collab_asdm_sync_stage s on s.id=f.sync_stage_id where s.job_id=? and f.publish_status='PENDING'",Integer.class,jobId);
        jdbc.update("update collab_asdm_sync_job set status=?,time_updated=now(3) where id=?",pending!=null&&pending>0?"PUBLISHING":"COMPLETED",jobId);
    }

    private void prepareBackfill(long syncStageId,long requirementId,long stageId,String head,
            CollabAsdmConfigurationService.RepositoryAccess access,CollabAsdmStageRules.Evaluation evaluated,
            Map<String,CollabAsdmGitlabClient.FileContent> rawByPath) {
        // Formal ASDM publication is the baseline; the approved index is never rewritten.
        jdbc.update("insert ignore into collab_asdm_published_file_registry(requirement_id,stage_id,source_path_sha256,source_path,latest_content_sha256,version_no,last_feishu_url) select ?,?,f.source_path_sha256,f.source_path,f.source_file_sha256,1,f.feishu_url from collab_asdm_package_file f join collab_asdm_package p on p.id=f.package_id where p.stage_id=? and f.publish_status='PUBLISHED' and f.source_file_sha256 is not null",requirementId,stageId,stageId);
        int news=0,updates=0;
        for(var file:evaluated.files()) {
            if(!file.selected()||!Set.of("CONFIRMED","LIKELY").contains(file.association()))continue;
            var blob=rawByPath.get(file.path());
            if(blob==null)blob=gitlab.getFileBytes(access.projectId(),file.path(),head,access.token(),access.repositoryBase(),properties.maxFileBytes());
            String hash=sha256(blob.content()),pathHash=sha256(file.path().getBytes(StandardCharsets.UTF_8));
            String before=jdbc.query("select latest_content_sha256 from collab_asdm_published_file_registry where requirement_id=? and stage_id=? and source_path_sha256=?",rs->rs.next()?rs.getString(1):null,requirementId,stageId,pathHash);
            if(hash.equals(before))continue;
            String action=before==null?"NEW":"UPDATED";
            if(before==null)news++;else updates++;
            var snapshot=snapshots.store(syncStageId,file.path(),blob.content(),mime(file.path()));
            String gitUrl=access.repositoryUrl()+"/-/blob/"+head+"/"+file.path();
            jdbc.update("insert into collab_asdm_sync_file(sync_stage_id,source_path_sha256,source_path,blob_sha,content_sha256,size_bytes,media_type,classification,delivery_mode,sync_action,snapshot_storage_key,source_git_url) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                    syncStageId,pathHash,file.path(),blob.blobSha(),hash,blob.size(),mime(file.path()),file.association(),file.deliveryMode(),action,snapshot.storageKey(),gitUrl);
        }
        jdbc.update("update collab_asdm_sync_stage set new_count=?,updated_count=?,status=?,time_updated=now(3) where id=?",news,updates,news+updates>0?"PENDING":"NO_CHANGES",syncStageId);
    }

    private void persistPreview(long syncStageId,long requirementId,long stageId,String head,
            CollabAsdmConfigurationService.RepositoryAccess access,CollabAsdmStageRules.Evaluation evaluated,
            Map<String,CollabAsdmGitlabClient.FileContent> rawByPath) {
        int news=0,updates=0;
        for(var file:evaluated.files()) {
            var blob=rawByPath.get(file.path());
            if(blob==null)continue;
            String hash=sha256(blob.content()),pathHash=sha256(file.path().getBytes(StandardCharsets.UTF_8));
            String before=jdbc.query("select latest_content_sha256 from collab_asdm_published_file_registry where requirement_id=? and stage_id=? and source_path_sha256=?",rs->rs.next()?rs.getString(1):null,requirementId,stageId,pathHash);
            String action=before==null?"NEW":before.equals(hash)?"UNCHANGED":"UPDATED";
            if("NEW".equals(action))news++;else if("UPDATED".equals(action))updates++;
            jdbc.update("insert into collab_asdm_sync_file(sync_stage_id,source_path_sha256,source_path,blob_sha,content_sha256,size_bytes,media_type,classification,delivery_mode,sync_action,publish_status,source_git_url) values(?,?,?,?,?,?,?,?,?,?,'PREVIEW',?)",
                    syncStageId,pathHash,file.path(),blob.blobSha(),hash,blob.size(),mime(file.path()),file.association(),file.deliveryMode(),action,
                    access.repositoryUrl()+"/-/blob/"+head+"/"+file.path());
        }
        jdbc.update("update collab_asdm_sync_stage set new_count=?,updated_count=?,time_updated=now(3) where id=?",news,updates,syncStageId);
    }

    private void publish(long jobId) {
        var stages=jdbc.queryForList("select id,stage_id,stage_key,status,supplemental_node_token,supplemental_index_token from collab_asdm_sync_stage where job_id=? and action='BACKFILL' and status in ('PENDING','PUBLISHING')",jobId);
        for(var stage:stages)try {publishStage(jobId,stage);}catch(Exception ex){stageStatus(num(stage,"id"),"FAILED",code(ex));}
        Integer failures=jdbc.queryForObject("select count(*) from collab_asdm_sync_stage where job_id=? and status='FAILED'",Integer.class,jobId);
        Integer pending=jdbc.queryForObject("select count(*) from collab_asdm_sync_stage where job_id=? and status in ('PENDING','PUBLISHING')",Integer.class,jobId);
        jdbc.update("update collab_asdm_sync_job set status=?,time_updated=now(3) where id=?",
                pending!=null&&pending>0?"PUBLISHING":failures!=null&&failures>0?"PARTIAL_FAILED":"COMPLETED",jobId);
    }

    private void publishStage(long jobId,Map<String,Object> stage) {
        long syncStageId=num(stage,"id"); long stageId=num(stage,"stage_id");
        var target=jdbc.queryForMap("select b.wiki_space_id,b.wiki_parent_node_token,b.publish_actor_user_id,b.chat_id,p.id requirement_id,s.title stage_title,s.stage_order,j.head_sha,j.rule_hash,j.default_version,j.group_revision,j.requested_by from collab_asdm_sync_job j join collab_project p on p.id=j.requirement_id join collab_stage s on s.id=? join collab_feishu_binding b on b.project_id=p.id where j.id=?",stageId,jobId);
        long actor=num(target,"publish_actor_user_id");String space=str(target,"wiki_space_id");
        String node=(String)stage.get("supplemental_node_token");
        if(node==null||node.isBlank()) {
            if("PUBLISHING".equals(stage.get("status")))throw new IllegalStateException("ASDM_FEISHU_FOLDER_RECONCILE_REQUIRED");
            String stageParent=jdbc.query("select stage_wiki_node_token from collab_asdm_package where stage_id=? and stage_wiki_node_token is not null order by id desc limit 1",rs->rs.next()?rs.getString(1):null,stageId);
            String title=stageParent==null
                    ?String.format("%02d-%s-补充同步-%s",((Number)target.get("stage_order")).intValue(),str(target,"stage_title"),LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                    :"补充同步-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            stageStatus(syncStageId,"PUBLISHING",null);
            var folder=attachments.createWikiFolder(actor,space,stageParent==null?str(target,"wiki_parent_node_token"):stageParent,title);
            node=folder.nodeToken();
            jdbc.update("update collab_asdm_sync_stage set supplemental_node_token=?,status='PUBLISHING',time_updated=now(3) where id=?",node,syncStageId);
        }
        List<Map<String,Object>> files=jdbc.queryForList("select * from collab_asdm_sync_file where sync_stage_id=? order by id",syncStageId);
        for(var file:files) {
            if("PUBLISHED".equals(file.get("publish_status")))continue;
            if("RECONCILE_REQUIRED".equals(file.get("publish_status")))continue;
            try {publishFile(actor,space,node,str(target,"chat_id"),file);}
            catch(Exception ex){
                String error=code(ex);
                String status=error.contains("99991679")||error.startsWith("Feishu API rejected request")
                        ||error.startsWith("ASDM_FILE_")||error.startsWith("ASDM_SNAPSHOT_")?"FAILED":"RECONCILE_REQUIRED";
                jdbc.update("update collab_asdm_sync_file set publish_status=?,last_error_code=?,attempts=attempts+1,time_updated=now(3) where id=?",status,error,num(file,"id"));
            }
        }
        Integer failed=jdbc.queryForObject("select count(*) from collab_asdm_sync_file where sync_stage_id=? and publish_status<>'PUBLISHED'",Integer.class,syncStageId);
        if(failed!=null&&failed>0){stageStatus(syncStageId,"FAILED","ASDM_SUPPLEMENTAL_FILE_FAILED");return;}
        String indexToken=(String)stage.get("supplemental_index_token");
        if(indexToken==null||indexToken.isBlank()) {
            var doc=documents.createWikiDocx(actor,space,node,"补充同步索引");
            indexToken=doc.path("documentToken").asText();
            jdbc.update("update collab_asdm_sync_stage set supplemental_index_token=?,supplemental_index_url=?,time_updated=now(3) where id=?",indexToken,doc.path("documentUrl").asText(),syncStageId);
        }
        StringBuilder index=new StringBuilder("# 补充同步索引\n\n**同步归档，未重新审批。** 原阶段审批结果和正式产物保持不变。\n\n")
                .append("Git commit: `").append(target.get("head_sha")).append("`\n\n规则哈希: `").append(target.get("rule_hash")).append("`\n\n同步人 ID: ").append(target.get("requested_by")).append("\n\n")
                .append("规则版本: ").append(target.get("default_version")).append("+g").append(target.get("group_revision")).append("\n\n")
                .append("| 来源路径 | 类型 | 飞书链接 |\n|---|---|---|\n");
        files=jdbc.queryForList("select source_path,sync_action,feishu_url from collab_asdm_sync_file where sync_stage_id=? order by id",syncStageId);
        for(var file:files)index.append("| `").append(str(file,"source_path").replace("|","\\|"))
                .append("` | ").append(file.get("sync_action")).append(" | [打开](").append(file.get("feishu_url")).append(") |\n");
        documents.replaceNativeDocx(actor,indexToken,index.toString());
        documents.grantChatReadAccess(actor,indexToken,"docx",str(target,"chat_id"));
        stageStatus(syncStageId,"COMPLETED",null);
    }

    private void publishFile(long actor,String space,String parent,String chatId,Map<String,Object> file) {
        long fileId=num(file,"id");
        if("UPLOADING".equals(file.get("publish_status")))throw new IllegalStateException("ASDM_FEISHU_RECONCILE_REQUIRED");
        byte[] bytes=snapshots.read(str(file,"snapshot_storage_key"),num(file,"size_bytes"),str(file,"content_sha256"));
        try {
            jdbc.update("update collab_asdm_sync_file set publish_status='UPLOADING',time_updated=now(3) where id=?",fileId);
            String title=basename(str(file,"source_path"));
            String token=(String)file.get("feishu_target_id"),node=(String)file.get("feishu_node_token"),url=(String)file.get("feishu_url");
            if("DOCX".equals(file.get("delivery_mode"))) {
                if(token==null) {
                    var created=documents.createWikiDocx(actor,space,parent,title);
                    token=created.path("documentToken").asText();node=created.path("nodeToken").asText();url=created.path("documentUrl").asText();
                    jdbc.update("update collab_asdm_sync_file set feishu_target_id=?,feishu_node_token=?,feishu_url=? where id=?",token,node,url,fileId);
                }
                documents.replaceNativeDocx(actor,token,new String(bytes,StandardCharsets.UTF_8));
                documents.grantChatReadAccess(actor,token,"docx",chatId);
            } else {
                if(token==null) {
                    token=attachments.uploadToDrive(actor,attachments.rootDriveFolderToken(actor),title,str(file,"media_type"),bytes);
                    jdbc.update("update collab_asdm_sync_file set feishu_target_id=? where id=?",token,fileId);
                }
                if(node==null) {
                    var mounted=attachments.mountDriveFile(actor,space,parent,token,title);
                    node=mounted.nodeToken();url=mounted.url();
                    jdbc.update("update collab_asdm_sync_file set feishu_node_token=?,feishu_url=? where id=?",node,url,fileId);
                }
                documents.grantChatReadAccess(actor,token,"file",chatId);
            }
            long stageId=jdbc.queryForObject("select s.stage_id from collab_asdm_sync_file f join collab_asdm_sync_stage s on s.id=f.sync_stage_id where f.id=?",Long.class,fileId);
            long requirementId=jdbc.queryForObject("select j.requirement_id from collab_asdm_sync_file f join collab_asdm_sync_stage s on s.id=f.sync_stage_id join collab_asdm_sync_job j on j.id=s.job_id where f.id=?",Long.class,fileId);
            jdbc.update("insert into collab_asdm_published_file_registry(requirement_id,stage_id,source_path_sha256,source_path,latest_content_sha256,last_feishu_url,last_sync_file_id) values(?,?,?,?,?,?,?) on duplicate key update version_no=if(latest_content_sha256=values(latest_content_sha256),version_no,version_no+1),latest_content_sha256=values(latest_content_sha256),last_feishu_url=values(last_feishu_url),last_sync_file_id=values(last_sync_file_id),time_updated=now(3)",requirementId,stageId,file.get("source_path_sha256"),file.get("source_path"),file.get("content_sha256"),url,fileId);
            jdbc.update("update collab_asdm_sync_file set publish_status='PUBLISHED',last_error_code=NULL,attempts=attempts+1,time_updated=now(3) where id=?",fileId);
            snapshots.delete(str(file,"snapshot_storage_key"));
        } finally {Arrays.fill(bytes,(byte)0);}
    }

    private List<FileView> files(long stageId) {return jdbc.query("select id,source_path,sync_action,publish_status,feishu_url,last_error_code from collab_asdm_sync_file where sync_stage_id=? order by id",(rs,n)->new FileView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),stageId);}
    private void stageStatus(long id,String status,String error){jdbc.update("update collab_asdm_sync_stage set status=?,last_error_code=?,time_updated=now(3) where id=?",status,error,id);}
    private void failJob(long id,Exception ex){
        log.warn("event=asdm.sync.failed job={} type={}",id,ex.getClass().getSimpleName());
        String status=jdbc.queryForObject("select status from collab_asdm_sync_job where id=?",String.class,id);
        if("PROCESSING".equals(status)) {
            jdbc.update("update collab_asdm_sync_stage set status='FAILED',last_error_code=?,time_updated=now(3) where job_id=? and status in ('PENDING','PUBLISHING')",code(ex),id);
            jdbc.update("update collab_asdm_sync_job set status='PARTIAL_FAILED',last_error_code=?,time_updated=now(3) where id=?",code(ex),id);
        } else jdbc.update("update collab_asdm_sync_job set status='FAILED',last_error_code=?,time_updated=now(3) where id=?",code(ex),id);
    }
    private String code(Exception ex){String m=ex.getMessage();return m==null?"ASDM_SYNC_FAILED":m.substring(0,Math.min(100,m.length()));}
    private String mime(String path){String p=path.toLowerCase(Locale.ROOT);if(p.endsWith(".md")||p.endsWith(".txt"))return "text/plain";if(p.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";if(p.endsWith(".png"))return "image/png";if(p.endsWith(".jpg")||p.endsWith(".jpeg"))return "image/jpeg";return "application/octet-stream";}
    private String basename(String path){int i=path.lastIndexOf('/');return i<0?path:path.substring(i+1);}
    private String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private long num(Map<String,Object> map,String key){return ((Number)map.get(key)).longValue();}
    private String str(Map<String,Object> map,String key){return Objects.toString(map.get(key),"");}
    private BusinessException bad(String code){return new BusinessException(code,code,HttpStatus.BAD_REQUEST);}
    private BusinessException conflict(String code){return new BusinessException(code,code,HttpStatus.CONFLICT);}
    public record SyncRequest(boolean applyLatestGroupRules,String scope,boolean includeCompletedStages) {}
    public record SyncAccepted(long syncJobId,String status,String message) {}
    public record JobView(long syncJobId,String status,String headSha,String ruleHash,String ruleVersion,int groupRevision,String lastErrorCode,List<StageView> stages) {}
    public record StageView(long syncStageId,String stageKey,String stageStatus,String action,String status,int foundCount,int newCount,int updatedCount,int uncertainCount,String supplementalIndexUrl,String lastErrorCode,Long candidateId,String candidateStatus,java.time.Instant notifyAfter,List<FileView> files) {}
    public record FileView(long syncFileId,String path,String syncAction,String publishStatus,String feishuUrl,String lastErrorCode) {}
    private record JobRow(long id,long requirementId,int groupRevision,String defaultVersion,String ruleHash,String headSha,String status,String error) {}
    private record Binding(String branch,String path) {}
}
