package com.company.skillplatform.collabproject.asdm.application;

import com.company.skillplatform.collabproject.application.CollabFeishuOutbox;
import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient.FileContent;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabClient.TreeEntry;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmGitlabException;
import com.company.skillplatform.collabproject.asdm.infrastructure.CollabAsdmProperties;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Polls configured ASDM branches, binds new REQ directories, and debounces requirement documents. */
@Service
public class CollabAsdmDetectionService {
    private static final Logger log = LoggerFactory.getLogger(CollabAsdmDetectionService.class);
    private static final String REQUIREMENTS_DIR = "workspace/requirements";
    private static final String REQUIREMENT_FILE = "requirement-doc.md";
    private static final Set<String> TERMINAL_CANDIDATES = Set.of("CLAIMED", "CLOSED_BY_SKILL", "INVALIDATED", "SUPERSEDED");

    private final JdbcTemplate jdbc;
    private final CollabAsdmProperties properties;
    private final CollabAsdmConfigurationService configuration;
    private final CollabAsdmGitlabClient gitlab;
    private final CollabProjectAuthorizationService authorization;
    private final CollabFeishuOutbox outbox;
    private final ObjectMapper mapper;
    private final CollabAsdmMultiStageDetectionService multiStageDetection;

    public CollabAsdmDetectionService(JdbcTemplate jdbc, CollabAsdmProperties properties,
            CollabAsdmConfigurationService configuration, CollabAsdmGitlabClient gitlab,
            CollabProjectAuthorizationService authorization, CollabFeishuOutbox outbox, ObjectMapper mapper,
            CollabAsdmMultiStageDetectionService multiStageDetection) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.configuration = configuration;
        this.gitlab = gitlab;
        this.authorization = authorization;
        this.outbox = outbox;
        this.mapper = mapper;
        this.multiStageDetection = multiStageDetection;
    }

    @Scheduled(fixedDelayString = "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('${ASDM_GITLAB_POLL_INTERVAL:60s}').toMillis()}")
    public void poll() {
        if (!properties.enabled()) return;
        drainRescans();
        List<Long> ids = jdbc.query("select p.id from collab_project p join collab_group g on g.id=p.group_id "
                + "join collab_asdm_credential c on c.group_id=g.id and c.status='VALID' "
                + "where p.status='ACTIVE' and p.asdm_branch is not null and p.asdm_binding_status not in ('DISABLED','AUTH_PAUSED') "
                + "and g.status='PUBLISHED' and g.asdm_repository_url is not null "
                + "and (p.asdm_last_scan_at is null or p.asdm_last_scan_at<=date_sub(now(3),interval ? microsecond)) "
                + "order by p.asdm_last_scan_at,p.id limit ?", (rs,n)->rs.getLong(1), properties.pollInterval().toMillis()*1000, properties.pollBatchSize());
        for (Long requirementId : ids) {
            int claimed = jdbc.update("update collab_project set asdm_last_scan_at=now(3),time_updated=now(3) where id=? and (asdm_last_scan_at is null or asdm_last_scan_at<=date_sub(now(3),interval ? microsecond))",
                    requirementId, properties.pollInterval().toMillis()*1000);
            if (claimed == 1) scanRequirement(requirementId);
        }
        releaseDueCandidates();
        multiStageDetection.releaseDueCandidates();
    }

    private void drainRescans() {
        List<Long> taskIds = jdbc.query("select id from collab_asdm_scan_task where status='PENDING' and available_at<=now(3) order by id limit ?", (rs,n)->rs.getLong(1), properties.pollBatchSize());
        for (Long id : taskIds) {
            int claimed = jdbc.update("update collab_asdm_scan_task set status='PROCESSING',claimed_at=now(3),attempts=attempts+1,time_updated=now(3) where id=? and status='PENDING'", id);
            if (claimed == 0) continue;
            Long requirementId = jdbc.query("select requirement_id from collab_asdm_scan_task where id=?", rs->rs.next()?(Long)rs.getObject(1):null, id);
            try {
                if (requirementId != null) scanRequirement(requirementId);
                else {
                    List<Long> groupRequirements=jdbc.query("select id from collab_project where group_id=(select group_id from collab_asdm_scan_task where id=?) and asdm_branch is not null and status='ACTIVE'",(rs,n)->rs.getLong(1),id);
                    for(Long rid:groupRequirements)scanRequirement(rid);
                }
                jdbc.update("update collab_asdm_scan_task set status='DONE',last_error_code=NULL,time_updated=now(3) where id=?",id);
            } catch (RuntimeException ex) {
                jdbc.update("update collab_asdm_scan_task set status='PENDING',available_at=date_add(now(3),interval 60 second),last_error_code=?,claimed_at=NULL,time_updated=now(3) where id=?",safeError(ex),id);
            }
        }
    }

    public void scanRequirement(long requirementId) {
        RequirementScan requirement = requirement(requirementId);
        if (requirement == null || requirement.branch() == null || requirement.groupId() == null) return;
        if (!"ACTIVE".equals(requirement.projectStatus()) || !"PUBLISHED".equals(requirement.groupStatus())) return;
        CollabAsdmConfigurationService.RepositoryAccess access = configuration.requireAccess(requirement.groupId());
        try {
            CollabAsdmGitlabClient.BranchInfo branch = gitlab.getBranch(access.projectId(), requirement.branch(), access.token(), access.repositoryBase());
            if (requirement.path() == null || requirement.path().isBlank()) {
                bindNewDirectory(requirement, access, branch.headSha());
                requirement = requirement(requirementId);
                if (requirement == null || requirement.path() == null) {
                    successfulScan(requirementId, branch.headSha());
                    return;
                }
            }
            if(!properties.multiStageEnabled()) detectFileCandidate(requirement, access, branch.headSha());
            multiStageDetection.scanRequirement(requirementId);
            successfulScan(requirementId, branch.headSha());
        } catch (CollabAsdmGitlabException ex) {
            if (ex.httpStatus()==401||ex.httpStatus()==403) pauseCredential(requirement.groupId(),ex.errorCode());
            else if (ex.httpStatus()==404 && requirement.path()!=null) setBindingStatus(requirementId,"DIRECTORY_MISSING",ex.errorCode());
            else if (ex.httpStatus()==404 && requirement.baselineHead()!=null) setBindingStatus(requirementId,"ERROR","ASDM_BASELINE_NOT_ACCESSIBLE");
            else log.warn("event=asdm.scan.failed requirementId={} errorCode={} httpStatus={}",requirementId,ex.errorCode(),ex.httpStatus());
        } catch (RuntimeException ex) {
            log.warn("event=asdm.scan.failed requirementId={} errorCode={}", requirementId, safeError(ex));
        }
    }

    private void bindNewDirectory(RequirementScan requirement, CollabAsdmConfigurationService.RepositoryAccess access, String currentHead) {
        if (requirement.baselineHead() == null || requirement.baselineHead().isBlank()) {
            setBindingStatus(requirement.id(), "ERROR", "ASDM_BASELINE_MISSING");
            return;
        }
        if (requirement.baselineHead().equals(currentHead)) {
            jdbc.update("update collab_project set asdm_binding_status='WAITING_DIRECTORY',asdm_last_scanned_head_sha=?,time_updated=now(3) where id=?",currentHead,requirement.id());
            return;
        }
        Set<String> before = requirementDirectories(access, requirement.baselineHead());
        Set<String> after = requirementDirectories(access, currentHead);
        after.removeAll(before);
        if (after.isEmpty()) {
            jdbc.update("update collab_project set asdm_binding_status='WAITING_DIRECTORY',asdm_last_scanned_head_sha=?,time_updated=now(3) where id=?",currentHead,requirement.id());
            return;
        }
        if (after.size()>1) {
            setBindingStatus(requirement.id(), "AMBIGUOUS", "ASDM_MULTIPLE_DIRECTORIES");
            audit(requirement, "ASDM_MULTIPLE_DIRECTORIES", Map.of("count",after.size(),"headSha",currentHead));
            return;
        }
        String path=after.iterator().next();
        if (directoryInUse(requirement.groupId(),requirement.id(),path)) {
            setBindingStatus(requirement.id(), "ERROR", "ASDM_DIRECTORY_ALREADY_BOUND");
            return;
        }
        int changed=jdbc.update("update collab_project set asdm_requirement_path=?,asdm_binding_status='BOUND',asdm_last_scanned_head_sha=?,version_no=version_no+1,time_updated=now(3) where id=? and asdm_requirement_path is null",path,currentHead,requirement.id());
        if(changed==1)audit(requirement,"ASDM_DIRECTORY_BOUND",Map.of("path",path,"headSha",currentHead));
    }

    private void detectFileCandidate(RequirementScan requirement, CollabAsdmConfigurationService.RepositoryAccess access, String head) {
        StageScan stage = requirementStage(requirement.id());
        if(stage==null||stage.actorsFrozenAt()==null||!Set.of("IN_PROGRESS","REWORK").contains(stage.status()))return;
        Integer ownerCount=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty='OWNER' and a.frozen_at is not null",Integer.class,requirement.groupId(),stage.id());
        if(ownerCount==null||ownerCount==0)return;
        Integer claimed=jdbc.queryForObject("select count(*) from collab_submission where stage_id=? and attempt_no=?",Integer.class,stage.id(),stage.attemptNo());
        if(claimed!=null&&claimed>0){closeCandidateBySubmission(stage.id(),stage.attemptNo());return;}
        String filePath=requirement.path()+"/"+REQUIREMENT_FILE;
        FileContent file;
        try { file=gitlab.getFile(access.projectId(),filePath,head,access.token(),access.repositoryBase()); }
        catch(CollabAsdmGitlabException ex){if(ex.httpStatus()==404){
            try{gitlab.listTree(access.projectId(),head,requirement.path(),access.token(),properties.maxTreeEntries(),access.repositoryBase());setBindingStatus(requirement.id(),"BOUND",null);}
            catch(CollabAsdmGitlabException directoryError){if(directoryError.httpStatus()==404)setBindingStatus(requirement.id(),"DIRECTORY_MISSING",directoryError.errorCode());else throw directoryError;}
            return;
        }throw ex;}
        jdbc.update("update collab_project set asdm_binding_status='BOUND',time_updated=now(3) where id=? and asdm_binding_status in ('AUTH_PAUSED','DIRECTORY_MISSING')",requirement.id());
        String markdown=decodeMarkdown(file.content(),file.size());
        String contentSha=sha256(markdown);
        Integer duplicate=jdbc.queryForObject("select count(*) from collab_artifact_version av join collab_submission sub on sub.artifact_version_id=av.id where sub.stage_id=? and JSON_UNQUOTE(JSON_EXTRACT(av.source_ref_json,'$.contentSha256'))=?",Integer.class,stage.id(),contentSha);
        if(duplicate!=null&&duplicate>0)return;
        Map<String,Object> existing=jdbc.query("select id,content_sha256,status from collab_asdm_candidate where stage_id=? and attempt_no=?",rs->rs.next()?Map.of("id",rs.getLong(1),"content",rs.getString(2),"status",rs.getString(3)):null,stage.id(),stage.attemptNo());
        if(existing!=null&&TERMINAL_CANDIDATES.contains(String.valueOf(existing.get("status"))))return;
        Instant now=Instant.now();
        if(existing==null){
            jdbc.update("insert into collab_asdm_candidate(requirement_id,stage_id,attempt_no,source_path,source_commit_sha,source_blob_sha,content_sha256,markdown_snapshot,snapshot_cached_at,detected_revision,status,first_detected_at,last_changed_at,notify_after,version_no,time_updated) values(?,?,?,?,?,?,?,?,?,1,'DEBOUNCING',?,?,?,0,?)",
                    requirement.id(),stage.id(),stage.attemptNo(),filePath,head,file.blobSha(),contentSha,markdown,now,now,now,now.plus(properties.debounce()),now);
            audit(requirement,"ASDM_CANDIDATE_UPDATED",Map.of("stageId",stage.id(),"attemptNo",stage.attemptNo(),"revision",1));
        }else if(!Objects.equals(existing.get("content"),contentSha)){
            long candidateId=((Number)existing.get("id")).longValue();
            jdbc.update("update collab_asdm_candidate set source_path=?,source_commit_sha=?,source_blob_sha=?,content_sha256=?,markdown_snapshot=?,snapshot_cached_at=?,detected_revision=detected_revision+1,status='DEBOUNCING',last_changed_at=?,notify_after=?,notified_revision=NULL,notified_at=NULL,confirmed_by=NULL,confirmed_at=NULL,submission_id=NULL,last_error_code=NULL,version_no=version_no+1,time_updated=? where id=? and status not in ('CLAIMED','CLOSED_BY_SKILL','INVALIDATED','SUPERSEDED')",
                    filePath,head,file.blobSha(),contentSha,markdown,now,now,now.plus(properties.debounce()),now,candidateId);
            audit(requirement,"ASDM_CANDIDATE_UPDATED",Map.of("candidateId",candidateId,"changed",true));
        }
    }

    @Transactional
    public void releaseDueCandidates() {
        List<Map<String,Object>> due=jdbc.queryForList("select c.id,c.requirement_id,c.detected_revision,p.status project_status,s.status stage_status,s.attempt_no from collab_asdm_candidate c join collab_project p on p.id=c.requirement_id join collab_stage s on s.id=c.stage_id where c.stage_key is null and c.status='DEBOUNCING' and c.notify_after<=now(3) and c.markdown_snapshot is not null and p.status='ACTIVE' and s.stage_key='REQUIREMENT' and s.status in ('IN_PROGRESS','REWORK') and s.attempt_no=c.attempt_no and s.actors_frozen_at is not null and not exists(select 1 from collab_submission sub where sub.stage_id=s.id and sub.attempt_no=s.attempt_no) order by c.notify_after limit ?",properties.pollBatchSize());
        for(Map<String,Object> candidate:due){
            long id=((Number)candidate.get("id")).longValue(); long requirementId=((Number)candidate.get("requirement_id")).longValue(); int revision=((Number)candidate.get("detected_revision")).intValue();
            int changed=jdbc.update("update collab_asdm_candidate c set status='NOTIFIED',notified_revision=c.detected_revision,notified_at=now(3),version_no=version_no+1,time_updated=now(3) "
                    + "where c.id=? and c.status='DEBOUNCING' and c.notify_after<=now(3) and c.markdown_snapshot is not null "
                    + "and exists(select 1 from collab_project p join collab_stage s on s.project_id=p.id and s.stage_key='REQUIREMENT' "
                    + "where p.id=c.requirement_id and p.status='ACTIVE' and s.id=c.stage_id and s.status in ('IN_PROGRESS','REWORK') "
                    + "and s.attempt_no=c.attempt_no and s.actors_frozen_at is not null "
                    + "and not exists(select 1 from collab_submission sub where sub.stage_id=s.id and sub.attempt_no=s.attempt_no))",id);
            if(changed==1&&"ACTIVE".equals(candidate.get("project_status"))&&Set.of("IN_PROGRESS","REWORK").contains(String.valueOf(candidate.get("stage_status"))))outbox.enqueueAsdmPublishCandidate(requirementId,id,revision);
        }
    }

    @Transactional
    public AsdmRequirementView view(String requirementKey,long actorId){
        var p=authorization.requireMemberOrThrow(requirementKey,actorId);
        return requirementView(p.projectId());
    }

    @Transactional
    public ScanAccepted scanNow(String requirementKey,long actorId){
        var p=authorization.requireRequirementManager(requirementKey,actorId);
        RequirementScan r=requirement(p.projectId());
        if(r==null||r.branch()==null)throw error("ASDM_REQUIREMENT_DISABLED","This requirement has no ASDM branch",HttpStatus.CONFLICT);
        Integer recent=jdbc.queryForObject("select count(*) from collab_asdm_scan_task where requirement_id=? and task_type='RESCAN' and time_created>date_sub(now(3),interval 60 second)",Integer.class,p.projectId());
        if(recent!=null&&recent>0)throw error("ASDM_SCAN_RATE_LIMITED","A scan was recently requested; retry in one minute",HttpStatus.TOO_MANY_REQUESTS);
        Instant now=Instant.now();
        jdbc.update("insert into collab_asdm_scan_task(group_id,requirement_id,task_type,dedupe_key,status,available_at,time_created,time_updated) values(?,?,'RESCAN',?,'PENDING',?,?,?)",r.groupId(),p.projectId(),"asdm:manual-rescan:"+p.projectId()+":"+UUID.randomUUID(),now,now,now);
        return new ScanAccepted(true,now);
    }

    @Transactional(readOnly=true)
    public DirectoryCandidates directoryCandidates(String requirementKey,long actorId){
        var p=authorization.requireRequirementManager(requirementKey,actorId);
        RequirementScan r=requirement(p.projectId()); if(r==null||r.baselineHead()==null)throw error("ASDM_BASELINE_MISSING","The ASDM branch baseline is unavailable",HttpStatus.CONFLICT);
        CollabAsdmConfigurationService.RepositoryAccess access=configuration.requireAccess(r.groupId());
        try{
            String head=gitlab.getBranch(access.projectId(),r.branch(),access.token(),access.repositoryBase()).headSha();
            Set<String> before=requirementDirectories(access,r.baselineHead());Set<String> after=requirementDirectories(access,head);after.removeAll(before);
            List<DirectoryCandidate> items=after.stream().sorted().map(path->new DirectoryCandidate(path,path.substring(path.lastIndexOf('/')+1),head)).toList();
            return new DirectoryCandidates(items);
        }catch(CollabAsdmGitlabException ex){throw gitlabError(ex);}
    }

    public AsdmRequirementView bindDirectory(String requirementKey,String path,Integer expectedVersion,long actorId){
        var p=authorization.requireRequirementManager(requirementKey,actorId);
        RequirementScan r=requirement(p.projectId()); if(r==null)throw error("COLLAB_PROJECT_NOT_FOUND","Requirement not found",HttpStatus.NOT_FOUND);
        if(!Set.of("AMBIGUOUS","ERROR").contains(r.bindingStatus()))throw error("ASDM_DIRECTORY_BIND_NOT_ALLOWED","Directory binding is not available in the current state",HttpStatus.CONFLICT);
        if(expectedVersion==null||expectedVersion!=r.versionNo())throw error("COLLAB_GROUP_VERSION_CONFLICT","Requirement changed; reload and retry",HttpStatus.CONFLICT);
        DirectoryCandidates candidates=directoryCandidates(requirementKey,actorId);
        DirectoryCandidate chosen=candidates.items().stream().filter(item->item.path().equals(path)).findFirst().orElseThrow(()->error("ASDM_DIRECTORY_NOT_CANDIDATE","Choose a newly created REQ directory on the configured branch",HttpStatus.UNPROCESSABLE_ENTITY));
        if(directoryInUse(r.groupId(),r.id(),path))throw error("ASDM_DIRECTORY_ALREADY_BOUND","This directory is already bound to another requirement",HttpStatus.CONFLICT);
        int updated=jdbc.update("update collab_project set asdm_requirement_path=?,asdm_binding_status='BOUND',asdm_last_scanned_head_sha=?,version_no=version_no+1,time_updated=now(3) where id=? and version_no=? and asdm_requirement_path is null",path,chosen.commitSha(),r.id(),expectedVersion);
        if(updated!=1)throw error("COLLAB_GROUP_VERSION_CONFLICT","Requirement changed; reload and retry",HttpStatus.CONFLICT);
        audit(r,"ASDM_DIRECTORY_BOUND",Map.of("path",path,"actorId",actorId,"manual",true));
        return requirementView(r.id());
    }

    @Transactional
    public ConfirmRequest requestPublish(long candidateId,int revision,long actorId){
        Map<String,Object> row=jdbc.query("select c.id,c.requirement_id,c.stage_id,c.attempt_no,c.detected_revision,c.status,c.notified_revision,p.project_key,p.group_id,s.status stage_status,s.actors_frozen_at from collab_asdm_candidate c join collab_project p on p.id=c.requirement_id join collab_stage s on s.id=c.stage_id where c.id=?",rs->rs.next()?row(rs):null,candidateId);
        if(row==null)throw error("ASDM_CANDIDATE_NOT_FOUND","ASDM candidate not found",HttpStatus.NOT_FOUND);
        String projectKey=(String)row.get("project_key");var project=authorization.requireMemberOrThrow(projectKey,actorId);
        long stageId=((Number)row.get("stage_id")).longValue();
        Integer owner=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty='OWNER' and a.user_id=? and a.frozen_at is not null",Integer.class,project.groupId(),stageId,actorId);
        if(owner==null||owner==0)throw error("COLLAB_STAGE_OWNER_REQUIRED","Only a frozen current stage owner may publish this candidate",HttpStatus.FORBIDDEN);
        int expectedAttempt=((Number)row.get("attempt_no")).intValue();
        if(!"NOTIFIED".equals(row.get("status"))||!Objects.equals(((Number)row.get("detected_revision")).intValue(),revision)||!Objects.equals(row.get("notified_revision")==null?null:((Number)row.get("notified_revision")).intValue(),revision))throw error("ASDM_CANDIDATE_STALE","Candidate card is outdated; use the latest notification",HttpStatus.CONFLICT);
        if(!Set.of("IN_PROGRESS","REWORK").contains(String.valueOf(row.get("stage_status")))||row.get("actors_frozen_at")==null)throw error("ASDM_CANDIDATE_STALE","The requirement stage is no longer accepting this candidate",HttpStatus.CONFLICT);
        Instant now=Instant.now();
        int changed=jdbc.update("update collab_asdm_candidate set status='CONFIRM_REQUESTED',confirmed_by=?,confirmed_at=?,version_no=version_no+1,time_updated=? where id=? and status='NOTIFIED' and detected_revision=? and notified_revision=? and attempt_no=?",actorId,now,now,candidateId,revision,revision,expectedAttempt);
        if(changed!=1)throw error("ASDM_CANDIDATE_STALE","Candidate card is outdated or already used",HttpStatus.CONFLICT);
        outbox.enqueueAsdmConfirmPublish(project.projectId(),candidateId,revision,actorId);
        audit(requirement(project.projectId()),"ASDM_PUBLISH_CLICKED",Map.of("candidateId",candidateId,"revision",revision,"actorId",actorId));
        return new ConfirmRequest(candidateId,revision,"CONFIRM_REQUESTED");
    }

    /** Worker-only GitLab revalidation followed by the shared transactional flow claim. */
    public ConfirmWorkerResult revalidateForPublish(long candidateId,int revision){
        Map<String,Object> row=jdbc.query("select c.id,c.requirement_id,c.stage_id,c.attempt_no,c.detected_revision,c.status,c.stage_key,c.source_path,c.source_commit_sha,c.source_blob_sha,c.content_sha256,c.markdown_snapshot,c.confirmed_by,p.project_key,p.group_id,p.asdm_branch,s.status stage_status,s.actors_frozen_at from collab_asdm_candidate c join collab_project p on p.id=c.requirement_id join collab_stage s on s.id=c.stage_id where c.id=?",rs->rs.next()?row(rs):null,candidateId);
        if(row==null)return ConfirmWorkerResult.staleResult();
        if(!"CONFIRM_REQUESTED".equals(row.get("status"))||((Number)row.get("detected_revision")).intValue()!=revision)return ConfirmWorkerResult.staleResult();
        long requirementId=((Number)row.get("requirement_id")).longValue();long groupId=((Number)row.get("group_id")).longValue();
        try{
            CollabAsdmConfigurationService.RepositoryAccess access=configuration.requireAccess(groupId);
            String branch=String.valueOf(row.get("asdm_branch"));
            if (row.get("stage_key") != null) return revalidateMultiStage(candidateId,revision,row,access,branch,requirementId);
            FileContent pinnedFile;
            try {
                pinnedFile=gitlab.getFile(access.projectId(),String.valueOf(row.get("source_path")),String.valueOf(row.get("source_commit_sha")),access.token(),access.repositoryBase());
            } catch (CollabAsdmGitlabException missingPinnedSnapshot) {
                if (missingPinnedSnapshot.httpStatus()==404) {
                    jdbc.update("update collab_asdm_candidate set status='ERROR',last_error_code='ASDM_PINNED_SNAPSHOT_NOT_ACCESSIBLE',version_no=version_no+1,time_updated=now(3) where id=? and status='CONFIRM_REQUESTED'",candidateId);
                    return ConfirmWorkerResult.changedResult();
                }
                throw missingPinnedSnapshot;
            }
            String pinnedSha=sha256(decodeMarkdown(pinnedFile.content(),pinnedFile.size()));
            if(!pinnedSha.equals(row.get("content_sha256"))||!Objects.equals(pinnedFile.blobSha(),row.get("source_blob_sha"))){
                jdbc.update("update collab_asdm_candidate set status='ERROR',last_error_code='ASDM_PINNED_SNAPSHOT_MISMATCH',version_no=version_no+1,time_updated=now(3) where id=? and status='CONFIRM_REQUESTED'",candidateId);
                return ConfirmWorkerResult.changedResult();
            }
            String head=gitlab.getBranch(access.projectId(),branch,access.token(),access.repositoryBase()).headSha();
            FileContent current;
            try { current=gitlab.getFile(access.projectId(),String.valueOf(row.get("source_path")),head,access.token(),access.repositoryBase()); }
            catch(CollabAsdmGitlabException missingCurrent){
                if(missingCurrent.httpStatus()==404){
                    jdbc.update("update collab_asdm_candidate set status='INVALIDATED',last_error_code='ASDM_FILE_MISSING',version_no=version_no+1,time_updated=now(3) where id=? and status='CONFIRM_REQUESTED'",candidateId);
                    return ConfirmWorkerResult.changedResult();
                }
                throw missingCurrent;
            }
            String currentSha=sha256(decodeMarkdown(current.content(),current.size()));
            if(!currentSha.equals(row.get("content_sha256"))){scanRequirement(requirementId);return ConfirmWorkerResult.changedResult();}
            String snapshotSha=sha256(String.valueOf(row.get("markdown_snapshot")));
            if(!snapshotSha.equals(row.get("content_sha256")))throw error("ASDM_CANDIDATE_SNAPSHOT_INVALID","Candidate snapshot failed integrity validation",HttpStatus.CONFLICT);
            return ConfirmWorkerResult.current((String)row.get("project_key"),candidateId,revision,((Number)row.get("confirmed_by")).longValue());
        }catch(CollabAsdmGitlabException ex){
            if(ex.httpStatus()==401||ex.httpStatus()==403)pauseCredential(groupId,ex.errorCode());
            throw gitlabError(ex);
        }
    }

    private ConfirmWorkerResult revalidateMultiStage(long candidateId,int revision,Map<String,Object> row,
            CollabAsdmConfigurationService.RepositoryAccess access,String branch,long requirementId) {
        List<com.company.skillplatform.collabproject.asdm.publication.CollabAsdmCandidateFileRepository.CandidateFile> files =
                multiStageDetection.candidateFiles(candidateId,revision);
        List<com.company.skillplatform.collabproject.asdm.publication.CollabAsdmCandidateFileRepository.CandidateFile> selected =
                files.stream().filter(com.company.skillplatform.collabproject.asdm.publication.CollabAsdmCandidateFileRepository.CandidateFile::selected).toList();
        if(selected.isEmpty())return ConfirmWorkerResult.staleResult();
        String commit=String.valueOf(row.get("source_commit_sha"));
        String head=gitlab.getBranch(access.projectId(),branch,access.token(),access.repositoryBase()).headSha();
        for(var file:selected){
            FileContent pinned=gitlab.getFileBytes(access.projectId(),file.sourcePath(),commit,access.token(),access.repositoryBase(),properties.maxFileBytes());
            if(!Objects.equals(pinned.blobSha(),file.sourceBlobSha())||pinned.size()!=file.sizeBytes()){
                jdbc.update("update collab_asdm_candidate set status='ERROR',last_error_code='ASDM_PINNED_SNAPSHOT_MISMATCH',version_no=version_no+1,time_updated=now(3) where id=? and status='CONFIRM_REQUESTED'",candidateId);
                return ConfirmWorkerResult.changedResult();
            }
            FileContent current=gitlab.getFileBytes(access.projectId(),file.sourcePath(),head,access.token(),access.repositoryBase(),properties.maxFileBytes());
            if(!Objects.equals(current.blobSha(),file.sourceBlobSha())){
                multiStageDetection.scanRequirement(requirementId);
                return ConfirmWorkerResult.changedResult();
            }
        }
        Map<String,Object> state=jdbc.query("select c.status,c.detected_revision,s.attempt_no,s.status stage_status,s.actors_frozen_at from collab_asdm_candidate c join collab_stage s on s.id=c.stage_id where c.id=?",rs->rs.next()?row(rs):null,candidateId);
        if(state==null||!"CONFIRM_REQUESTED".equals(state.get("status"))||((Number)state.get("detected_revision")).intValue()!=revision
                ||((Number)state.get("attempt_no")).intValue()!=((Number)row.get("attempt_no")).intValue()
                ||!Set.of("IN_PROGRESS","REWORK").contains(String.valueOf(state.get("stage_status")))||state.get("actors_frozen_at")==null)
            return ConfirmWorkerResult.staleResult();
        return ConfirmWorkerResult.current((String)row.get("project_key"),candidateId,revision,((Number)row.get("confirmed_by")).longValue());
    }

    private void successfulScan(long requirementId,String head){jdbc.update("update collab_project set asdm_last_scanned_head_sha=?,asdm_last_scan_at=now(3),asdm_binding_status=case when asdm_binding_status='AUTH_PAUSED' and asdm_requirement_path is null then 'WAITING_DIRECTORY' when asdm_binding_status='AUTH_PAUSED' then 'BOUND' else asdm_binding_status end,time_updated=now(3) where id=?",head,requirementId);}
    private void closeCandidateBySubmission(long stageId,int attempt){jdbc.update("update collab_asdm_candidate set status='CLOSED_BY_SKILL',last_error_code='SUBMISSION_ALREADY_CLAIMED',version_no=version_no+1,time_updated=now(3) where stage_id=? and attempt_no=? and status not in ('CLAIMED','CLOSED_BY_SKILL','INVALIDATED','SUPERSEDED')",stageId,attempt);}
    private void setBindingStatus(long requirementId,String status,String errorCode){jdbc.update("update collab_project set asdm_binding_status=?,time_updated=now(3) where id=?",status,requirementId);if(errorCode!=null)jdbc.update("update collab_asdm_candidate set last_error_code=?,time_updated=now(3) where requirement_id=? and status in ('DEBOUNCING','NOTIFIED')",errorCode,requirementId);}
    private boolean directoryInUse(long groupId,long currentRequirementId,String path){Integer count=jdbc.queryForObject("select count(*) from collab_project where group_id=? and id<>? and asdm_requirement_path=?",Integer.class,groupId,currentRequirementId,path);return count!=null&&count>0;}
    private Set<String> requirementDirectories(CollabAsdmConfigurationService.RepositoryAccess access,String ref){
        List<TreeEntry> entries=gitlab.listTree(access.projectId(),ref,REQUIREMENTS_DIR,access.token(),properties.maxTreeEntries(),access.repositoryBase());
        Set<String> result=new LinkedHashSet<>();
        for(TreeEntry entry:entries){if(!"tree".equals(entry.type()))continue;String path=entry.path();String name=path.substring(path.lastIndexOf('/')+1);if(path.length()<=1024&&name.matches("^REQ-[A-Za-z0-9].*")&&!name.contains("..")&&name.chars().noneMatch(Character::isISOControl))result.add(path);}
        return result;
    }
    private String decodeMarkdown(byte[] bytes,long declaredSize){
        if(bytes==null||declaredSize>properties.maxMarkdownBytes()||bytes.length>properties.maxMarkdownBytes())throw error("ASDM_FILE_TOO_LARGE","requirement-doc.md exceeds the 1 MiB limit",HttpStatus.PAYLOAD_TOO_LARGE);
        if(bytes.length==0)throw error("ASDM_FILE_EMPTY","requirement-doc.md is empty",HttpStatus.UNPROCESSABLE_ENTITY);
        try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(text.isBlank())throw error("ASDM_FILE_EMPTY","requirement-doc.md is empty",HttpStatus.UNPROCESSABLE_ENTITY);return text;}catch(CharacterCodingException ex){throw error("ASDM_FILE_ENCODING_INVALID","requirement-doc.md must be UTF-8 Markdown",HttpStatus.UNPROCESSABLE_ENTITY);}
    }
    private RequirementScan requirement(long id){return jdbc.query("select p.id,p.project_key,p.name,p.group_id,p.status project_status,p.asdm_branch,p.asdm_requirement_path,p.asdm_binding_status,p.asdm_baseline_head_sha,p.asdm_last_scanned_head_sha,p.version_no,p.asdm_last_scan_at,g.status group_status,g.asdm_repository_url,g.asdm_gitlab_project_id from collab_project p left join collab_group g on g.id=p.group_id where p.id=?",rs->rs.next()?requirement(rs):null,id);}
    private RequirementScan requirement(ResultSet rs)throws SQLException{return new RequirementScan(rs.getLong("id"),rs.getString("project_key"),rs.getString("name"),(Long)rs.getObject("group_id"),rs.getString("project_status"),rs.getString("asdm_branch"),rs.getString("asdm_requirement_path"),rs.getString("asdm_binding_status"),rs.getString("asdm_baseline_head_sha"),rs.getString("asdm_last_scanned_head_sha"),rs.getInt("version_no"),rs.getString("group_status"),rs.getString("asdm_repository_url"),rs.getObject("asdm_gitlab_project_id")==null?null:rs.getLong("asdm_gitlab_project_id"));}
    private StageScan requirementStage(long requirementId){return jdbc.query("select id,status,attempt_no,actors_frozen_at from collab_stage where project_id=? and stage_key='REQUIREMENT'",rs->rs.next()?new StageScan(rs.getLong("id"),rs.getString("status"),rs.getInt("attempt_no"),rs.getTimestamp("actors_frozen_at")==null?null:rs.getTimestamp("actors_frozen_at").toInstant()):null,requirementId);}
    private AsdmRequirementView requirementView(long requirementId){RequirementScan r=requirement(requirementId);Map<String,Object> candidate=jdbc.query("select id,detected_revision,status,source_path,source_commit_sha,notify_after,last_error_code from collab_asdm_candidate where requirement_id=? order by id desc limit 1",rs->rs.next()?Map.of("id",rs.getLong("id"),"revision",rs.getInt("detected_revision"),"status",rs.getString("status"),"path",rs.getString("source_path"),"commit",rs.getString("source_commit_sha"),"notify",rs.getTimestamp("notify_after").toInstant(),"error",Objects.toString(rs.getString("last_error_code"),"")):null,requirementId);CandidateView latest=candidate==null?null:new CandidateView((Long)candidate.get("id"),(Integer)candidate.get("revision"),(String)candidate.get("status"),(String)candidate.get("path"),(String)candidate.get("commit"),(Instant)candidate.get("notify"));return new AsdmRequirementView(r.repositoryUrl()!=null,r.branch(),r.path(),r.bindingStatus(),r.lastScannedHead(),latest,r.versionNo(),candidate==null?null:((String)candidate.get("error")).isBlank()?null:(String)candidate.get("error"));}
    private void pauseCredential(long groupId,String error){
        int newlyPaused=jdbc.update("update collab_asdm_credential set status=case when ?='GITLAB_INVALID_TOKEN' then 'INVALID_TOKEN' else 'PERMISSION_DENIED' end,last_error_code=?,failure_notified_at=now(3),time_updated=now(3) where group_id=? and failure_notified_at is null",error,error,groupId);
        jdbc.update("update collab_project set asdm_binding_status='AUTH_PAUSED',time_updated=now(3) where group_id=? and asdm_branch is not null",groupId);
        if(newlyPaused==1){
            Long projectId=jdbc.query("select id from collab_project where group_id=? and asdm_branch is not null order by id limit 1",rs->rs.next()?rs.getLong(1):null,groupId);
            Integer credentialVersion=jdbc.queryForObject("select version_no from collab_asdm_credential where group_id=?",Integer.class,groupId);
            if(projectId!=null&&credentialVersion!=null)outbox.enqueueAsdmTokenInvalid(projectId,groupId,error,credentialVersion);
        }
    }
    private void audit(RequirementScan req,String event,Object payload){StageScan stage=requirementStage(req.id());if(stage==null)return;try{jdbc.update("insert into collab_stage_event(time_created,time_updated,project_id,stage_id,attempt_no,event_type,actor_user_id,from_status,to_status,reference_id,payload_json,created_at) values(now(3),now(3),?,?,? ,?,NULL,NULL,NULL,NULL,?,now(3))",req.id(),stage.id(),stage.attemptNo(),event,mapper.writeValueAsString(payload));}catch(JsonProcessingException ex){throw new IllegalStateException("Unable to persist ASDM audit event",ex);}}
    private BusinessException gitlabError(CollabAsdmGitlabException ex){HttpStatus status=ex.httpStatus()==401||ex.httpStatus()==403?HttpStatus.CONFLICT:ex.httpStatus()==404?HttpStatus.UNPROCESSABLE_ENTITY:HttpStatus.SERVICE_UNAVAILABLE;return error(ex.errorCode(),"GitLab request failed ("+ex.errorCode()+")",status);}
    private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String safeError(Throwable ex){return ex instanceof CollabAsdmGitlabException gitlab?gitlab.errorCode():ex instanceof BusinessException business?business.getCode():ex.getClass().getSimpleName();}
    private Map<String,Object> row(ResultSet rs)throws SQLException{Map<String,Object> m=new java.util.HashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)m.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));return m;}

    public record AsdmRequirementView(boolean enabled,String branch,String requirementPath,String bindingStatus,String lastScannedCommitSha,CandidateView latestCandidate,int versionNo,String lastErrorCode){}
    public record CandidateView(Long candidateId,Integer revision,String status,String filePath,String commitSha,Instant notifyAfter){}
    public record DirectoryCandidates(List<DirectoryCandidate> items){}
    public record DirectoryCandidate(String path,String name,String commitSha){}
    public record ScanAccepted(boolean accepted,Instant queuedAt){}
    public record ConfirmRequest(long candidateId,int revision,String status){}
    public record ConfirmWorkerResult(boolean stale,boolean changed,String projectKey,long candidateId,int revision,long actorId){static ConfirmWorkerResult staleResult(){return new ConfirmWorkerResult(true,false,null,0,0,0);}static ConfirmWorkerResult changedResult(){return new ConfirmWorkerResult(false,true,null,0,0,0);}static ConfirmWorkerResult current(String key,long id,int rev,long actor){return new ConfirmWorkerResult(false,false,key,id,rev,actor);}}
    private record RequirementScan(long id,String key,String name,Long groupId,String projectStatus,String branch,String path,String bindingStatus,String baselineHead,String lastScannedHead,int versionNo,String groupStatus,String repositoryUrl,Long gitlabProjectId){}
    private record StageScan(long id,String status,int attemptNo,Instant actorsFrozenAt){}
}
