package com.company.skillplatform.collabproject.flow.application;

import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.application.CollabFeishuOutbox;
import com.company.skillplatform.collabproject.application.CollabStagePromptFactory;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.ArtifactUpload;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.DecisionRequest;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.SubmissionRequest;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business rules for the independent manual collaboration workflow. */
@Service
public class CollabProjectFlowService {
    private static final int MAX_MARKDOWN_BYTES = 1024 * 1024;
    private static final Set<String> STAGE_KEYS = Set.of(
            "REQUIREMENT", "PRODUCT", "ARCHITECTURE_DESIGN", "UI_DESIGN",
            "FRONTEND_CODING", "BACKEND_CODING", "TESTING", "ACCEPTANCE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollabProjectAuthorizationService authorization;
    private final CollabFeishuOutbox feishuOutbox;

    public CollabProjectFlowService(JdbcTemplate jdbc, ObjectMapper mapper,
            CollabProjectAuthorizationService authorization, CollabFeishuOutbox feishuOutbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.authorization = authorization;
        this.feishuOutbox = feishuOutbox;
    }

    @Transactional(readOnly = true)
    public WorkflowView workflow(String projectKey, Long actorId) {
        var project = authorization.requireMemberOrThrow(projectKey, actorId);
        List<StageWorkflowView> stages = jdbc.query("select s.id,s.stage_key,s.stage_order,s.title,s.artifact_kind,s.git_required,s.owner_user_id,s.reviewer_user_id,s.status,s.attempt_no,s.version_no,sub.id submission_id,sub.status submission_status,sub.artifact_version_id,sub.submitted_at,ap.state approval_state,ap.reviewer_user_id frozen_reviewer_id,ap.version_no approval_version_no from collab_stage s left join collab_submission sub on sub.id=(select max(s2.id) from collab_submission s2 where s2.stage_id=s.id) left join collab_approval ap on ap.id=(select a0.id from collab_approval a0 where a0.submission_id=sub.id and a0.reviewer_user_id=? order by a0.id desc limit 1) where s.project_id=? order by s.stage_order",
                (rs,n)->new StageWorkflowView(rs.getLong("id"),rs.getString("stage_key"),rs.getInt("stage_order"),rs.getString("title"),rs.getString("artifact_kind"),rs.getBoolean("git_required"),rs.getLong("owner_user_id"),(Long)rs.getObject("reviewer_user_id"),additionalReviewerIds(rs.getLong("id")),rs.getString("status"),rs.getInt("attempt_no"),rs.getInt("version_no"),(Long)rs.getObject("submission_id"),rs.getString("submission_status"),(Long)rs.getObject("artifact_version_id"),instant(rs,"submitted_at"),rs.getString("approval_state"),(Long)rs.getObject("frozen_reviewer_id"),(Integer)rs.getObject("approval_version_no")),actorId,project.projectId());
        return new WorkflowView(project.projectKey(),project.approvalLevel(),project.status(),project.currentStageOrder(),stages);
    }

    @Transactional
    public ArtifactView upload(String projectKey, String stageKey, ArtifactUpload request, String idempotencyKey, Long actorId) {
        idempotencyKey=validateIdempotencyKey(idempotencyKey);
        var project = authorization.requireMemberOrThrow(projectKey, actorId);
        lockProjectFlow(project.projectId());
        String key = normalizeStage(stageKey);
        StageRow stage = lockedStage(project.projectId(), key);
        if (request.stageId() == null || !request.stageId().equals(stage.id()) || request.attemptNo() < 1)
            throw error("COLLAB_STAGE_VERSION_STALE", "Stage or attempt no longer matches", HttpStatus.CONFLICT);
        String kind = request.kind().trim().toUpperCase(Locale.ROOT);
        if (!stage.artifactKind().equals(kind)) throw error("COLLAB_ARTIFACT_KIND_INVALID", "Artifact kind does not match this stage", HttpStatus.UNPROCESSABLE_ENTITY);
        validateArtifact(stage, request);
        String fingerprint = sha256(json(Map.of("stageId",stage.id(),"attemptNo",request.attemptNo(),"kind",kind,"title",request.title().trim(),"sha256",request.sha256().toLowerCase(Locale.ROOT),"content",request.markdownContent(),"gitEvidence",request.gitEvidence()==null?Map.of():request.gitEvidence())));
        Map<String,Object> existing = jdbc.query("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.request_fingerprint,v.uploaded_by,a.kind,a.title,s.stage_key,pv.url from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where v.stage_id=? and v.upload_idempotency_key=?", rs -> rs.next()?row(rs):null, stage.id(), idempotencyKey);
        if (existing != null) {
            if (!Objects.equals(((Number)existing.get("uploaded_by")).longValue(), actorId)) throw error("IDEMPOTENCY_KEY_REUSED", "Idempotency key belongs to another uploader", HttpStatus.CONFLICT);
            if (!fingerprint.equals(existing.get("request_fingerprint"))) throw error("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for different content", HttpStatus.CONFLICT);
            return artifactView(existing);
        }
        requireStageOwner(stage, actorId);
        requireCurrentStage(project.currentStageOrder(), stage);
        if (request.attemptNo() != stage.attemptNo())
            throw error("COLLAB_STAGE_VERSION_STALE", "Stage or attempt no longer matches", HttpStatus.CONFLICT);
        int version = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from collab_artifact_version where stage_id=?", Integer.class, stage.id());
        Long artifactId = jdbc.query("select id from collab_artifact where stage_id=? and kind=? order by id limit 1", rs->rs.next()?rs.getLong(1):null,stage.id(),kind);
        Instant now=Instant.now();
        if (artifactId==null) {
            jdbc.update("insert into collab_artifact(time_created,time_updated,project_id,stage_id,kind,title,status) values(?,?,?,?,?,?,'ACTIVE')",now,now,project.projectId(),stage.id(),kind,request.title().trim());
            artifactId=jdbc.queryForObject("select id from collab_artifact where stage_id=? and kind=? order by id desc limit 1",Long.class,stage.id(),kind);
        }
        String gitJson=request.gitEvidence()==null?null:json(request.gitEvidence());
        jdbc.update("insert into collab_artifact_version(time_created,time_updated,artifact_id,stage_id,version_no,attempt_no,sha256,mime_type,size_bytes,markdown_content,uploaded_by,git_evidence_json,upload_idempotency_key,request_fingerprint) values(?,?,?,?,?,?,?,'text/markdown; charset=utf-8',?,?,?,?,?,?)",
                now,now,artifactId,stage.id(),version,stage.attemptNo(),request.sha256().toLowerCase(Locale.ROOT),request.markdownContent().getBytes(StandardCharsets.UTF_8).length,request.markdownContent(),actorId,gitJson,idempotencyKey,fingerprint);
        Long versionId=jdbc.queryForObject("select id from collab_artifact_version where stage_id=? and upload_idempotency_key=?",Long.class,stage.id(),idempotencyKey);
        jdbc.update("update collab_artifact set title=?,latest_version_id=?,time_updated=? where id=?",request.title().trim(),versionId,now,artifactId);
        addEvent(project.projectId(),stage,"ARTIFACT_UPLOADED",actorId,stage.status(),stage.status(),versionId,Map.of("artifactVersionId",versionId,"sha256",request.sha256().toLowerCase(Locale.ROOT)));
        return artifact(projectKey,versionId,actorId);
    }

    @Transactional
    public SubmissionView submit(String projectKey,String stageKey,SubmissionRequest request,String idempotencyKey,Long actorId) {
        idempotencyKey=validateIdempotencyKey(idempotencyKey);
        var project=authorization.requireMemberOrThrow(projectKey,actorId); String key=normalizeStage(stageKey);
        lockProjectFlow(project.projectId());
        StageRow stage=lockedStage(project.projectId(),key);
        if (!Objects.equals(stage.id(),request.stageId()) || request.attemptNo()<1) throw error("COLLAB_STAGE_VERSION_STALE","Stage or attempt no longer matches",HttpStatus.CONFLICT);
        String fingerprint=sha256(json(Map.of("stageId",stage.id(),"attemptNo",request.attemptNo(),"artifactVersionId",request.artifactVersionId(),"expectedStageVersion",request.expectedStageVersion())));
        Map<String,Object> prior=jdbc.query("select id,submitted_by,request_fingerprint from collab_submission where stage_id=? and idempotency_key=?",rs->rs.next()?Map.of("id",rs.getLong("id"),"submitted_by",rs.getLong("submitted_by"),"request_fingerprint",rs.getString("request_fingerprint")):null,stage.id(),idempotencyKey);
        if(prior!=null){if(!Objects.equals(((Number)prior.get("submitted_by")).longValue(),actorId)||!fingerprint.equals(prior.get("request_fingerprint")))throw error("IDEMPOTENCY_KEY_REUSED","Idempotency key was already used by another actor or for a different submission",HttpStatus.CONFLICT);return submission(projectKey,(Long)prior.get("id"),actorId);}
        requireStageOwner(stage,actorId); requireCurrentStage(project.currentStageOrder(),stage);
        if(stage.attemptNo()!=request.attemptNo()) throw error("COLLAB_STAGE_VERSION_STALE","Stage or attempt no longer matches",HttpStatus.CONFLICT);
        if(stage.versionNo()!=request.expectedStageVersion()) throw error("COLLAB_STAGE_VERSION_STALE","Stage changed; reload and retry",HttpStatus.CONFLICT);
        if(!Set.of("WAITING","IN_PROGRESS","REWORK").contains(stage.status()))throw error("COLLAB_STAGE_NOT_SUBMITTABLE","Stage is not accepting submissions",HttpStatus.CONFLICT);
        Map<String,Object> artifact=jdbc.query("select v.id,v.stage_id,v.attempt_no,v.artifact_id,a.kind,a.title,v.sha256 from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id where v.id=?",rs->rs.next()?Map.of("id",rs.getLong("id"),"stage_id",rs.getLong("stage_id"),"attempt_no",rs.getInt("attempt_no"),"artifact_id",rs.getLong("artifact_id"),"kind",rs.getString("kind"),"title",rs.getString("title"),"sha256",rs.getString("sha256")):null,request.artifactVersionId());
        if(artifact==null||!Objects.equals(((Number)artifact.get("stage_id")).longValue(),stage.id())||((Number)artifact.get("attempt_no")).intValue()!=stage.attemptNo())throw error("COLLAB_ARTIFACT_NOT_CURRENT","Artifact version does not belong to the current stage attempt",HttpStatus.UNPROCESSABLE_ENTITY);
        boolean manual=requiresReviewer(project.approvalLevel(),key);
        List<Long> reviewers=reviewerIds(project.projectId(),stage);
        if(manual&&reviewers.isEmpty())throw error("COLLAB_REVIEWER_UNAVAILABLE","At least one active reviewer is required",HttpStatus.UNPROCESSABLE_ENTITY);
        if(manual)for(Long reviewerId:reviewers)authorization.requireActiveMember(project.projectId(),reviewerId);
        String nextStatus=manual?"PENDING_APPROVAL":"COMPLETED";
        Instant now=Instant.now();
        jdbc.update("update collab_stage set status=?,version_no=version_no+1,completed_at=?,started_at=coalesce(started_at,?),time_updated=? where id=? and version_no=?",nextStatus,manual?null:now,now,now,stage.id(),stage.versionNo());
        int nextVersion=stage.versionNo()+1;
        Map<String,Object> policy=Map.of("approvalLevel",project.approvalLevel(),"approvalMode",manual?"MANUAL":"AUTOMATIC","reviewerUserIds",manual?reviewers:List.of());
        jdbc.update("insert into collab_submission(time_created,time_updated,stage_id,attempt_no,artifact_version_id,submitted_by,policy_snapshot,status,idempotency_key,request_fingerprint,submitted_at,decided_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",now,now,stage.id(),stage.attemptNo(),request.artifactVersionId(),actorId,json(policy),manual?"PENDING_APPROVAL":"APPROVED",idempotencyKey,fingerprint,now,manual?null:now);
        Long submissionId=jdbc.queryForObject("select id from collab_submission where stage_id=? and idempotency_key=?",Long.class,stage.id(),idempotencyKey);
        feishuOutbox.enqueuePublish(project.projectId(),request.artifactVersionId());
        if(manual) for(Long reviewerId:reviewers){
            jdbc.update("insert into collab_approval(time_created,time_updated,submission_id,reviewer_user_id,state,version_no) values(?,?,?,?,'PENDING',0)",now,now,submissionId,reviewerId);
            Long approvalId=jdbc.queryForObject("select id from collab_approval where submission_id=? and reviewer_user_id=?",Long.class,submissionId,reviewerId);
            feishuOutbox.enqueueApprovalReminder(project.projectId(),approvalId);
        }
        else completeStage(project.projectId(),stage,actorId,submissionId,now);
        addEvent(project.projectId(),stage,"SUBMITTED",actorId,stage.status(),nextStatus,submissionId,Map.of("artifactVersionId",request.artifactVersionId(),"approvalMode",manual?"MANUAL":"AUTOMATIC","stageVersion",nextVersion));
        return submission(projectKey,submissionId,actorId);
    }

    @Transactional
    public SubmissionView decide(String projectKey,Long submissionId,DecisionRequest request,Long actorId){
        var project=authorization.requireMemberOrThrow(projectKey,actorId);
        lockProjectFlow(project.projectId());
        Map<String,Object> row=jdbc.query("select sub.id,sub.stage_id,sub.attempt_no,sub.status,sub.artifact_version_id,ap.id approval_id,ap.reviewer_user_id,ap.state,ap.version_no approval_version,s.stage_key,s.status stage_status,s.version_no stage_version,s.owner_user_id,s.attempt_no current_attempt from collab_submission sub join collab_approval ap on ap.submission_id=sub.id join collab_stage s on s.id=sub.stage_id where sub.id=? and s.project_id=? and ap.reviewer_user_id=? for update",rs->rs.next()?row(rs):null,submissionId,project.projectId(),actorId);
        if(row==null)throw error("COLLAB_SUBMISSION_NOT_FOUND","Submission not found",HttpStatus.NOT_FOUND);
        Long reviewer=((Number)row.get("reviewer_user_id")).longValue();
        if(!reviewer.equals(actorId))throw error("COLLAB_APPROVER_REQUIRED","Only the assigned reviewer may decide",HttpStatus.FORBIDDEN);
        authorization.requireActiveMember(project.projectId(),actorId);
        if(!"PENDING".equals(row.get("state"))||!"PENDING_APPROVAL".equals(row.get("status")))throw error("COLLAB_SUBMISSION_ALREADY_DECIDED","Submission is no longer awaiting review",HttpStatus.CONFLICT);
        if(((Number)row.get("approval_version")).longValue()!=request.expectedVersion())throw error("COLLAB_APPROVAL_VERSION_STALE","Approval changed; reload and retry",HttpStatus.CONFLICT);
        String decision=request.decision().trim().toUpperCase(Locale.ROOT); if(!List.of("APPROVE","REJECT").contains(decision))throw error("COLLAB_DECISION_INVALID","Decision must be APPROVE or REJECT",HttpStatus.BAD_REQUEST);
        if("REJECT".equals(decision)&&(request.comment()==null||request.comment().isBlank()))throw error("COLLAB_REJECTION_REASON_REQUIRED","A reason is required when rejecting",HttpStatus.UNPROCESSABLE_ENTITY);
        Instant now=Instant.now(); String approvalState="APPROVE".equals(decision)?"APPROVED":"REJECTED";
        int changed=jdbc.update("update collab_approval set state=?,decision_comment=?,decided_at=?,version_no=version_no+1,time_updated=? where id=? and state='PENDING' and version_no=?",approvalState,request.comment(),now,now,row.get("approval_id"),request.expectedVersion());
        if(changed!=1)throw error("COLLAB_SUBMISSION_ALREADY_DECIDED","Submission was decided concurrently",HttpStatus.CONFLICT);
        StageRow stage=lockedStage(project.projectId(),(String)row.get("stage_key"));
        boolean rejected="REJECT".equals(decision);
        Integer remaining=jdbc.queryForObject("select count(*) from collab_approval where submission_id=? and state='PENDING'",Integer.class,submissionId);
        boolean allApproved=!rejected&&(remaining==null||remaining==0);
        if(rejected){
            jdbc.update("update collab_submission set status='REJECTED',decided_at=?,time_updated=? where id=? and status='PENDING_APPROVAL'",now,now,submissionId);
            jdbc.update("update collab_approval set state='CANCELLED',time_updated=? where submission_id=? and state='PENDING'",now,submissionId);
            jdbc.update("update collab_stage set status='REWORK',attempt_no=attempt_no+1,version_no=version_no+1,completed_at=null,time_updated=? where id=? and status='PENDING_APPROVAL'",now,stage.id());
            feishuOutbox.enqueueStageReworkReminder(project.projectId(),stage.id(),submissionId);
        }else if(allApproved){
            jdbc.update("update collab_submission set status='APPROVED',decided_at=?,time_updated=? where id=? and status='PENDING_APPROVAL'",now,now,submissionId);
            jdbc.update("update collab_stage set status='COMPLETED',completed_at=?,version_no=version_no+1,time_updated=? where id=? and status='PENDING_APPROVAL'",now,now,stage.id());
            completeStage(project.projectId(),stage,actorId,submissionId,now);
        }
        addEvent(project.projectId(),stage,rejected?"REJECTED":allApproved?"APPROVED":"REVIEWER_APPROVED",actorId,"PENDING_APPROVAL",rejected?"REWORK":allApproved?"COMPLETED":"PENDING_APPROVAL",submissionId,Map.of("comment",request.comment()==null?"":request.comment()));
        return submission(projectKey,submissionId,actorId);
    }

    @Transactional(readOnly=true)
    public ContextView context(String projectKey,String stageKey,boolean includeHistory,Long actorId){
        var project=authorization.requireMemberOrThrow(projectKey,actorId); String key=normalizeStage(stageKey);
        StageRow current=readStage(project.projectId(),key); boolean mayReadHistory=Objects.equals(project.ownerUserId(),actorId)||current.ownerUserId().equals(actorId)||Objects.equals(current.reviewerUserId(),actorId)||additionalReviewerIds(current.id()).contains(actorId);
        if(includeHistory&&!mayReadHistory)throw error("COLLAB_HISTORY_FORBIDDEN","History is limited to the project owner and current stage assignees",HttpStatus.FORBIDDEN);
        List<ArtifactView> approved=jdbc.query("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.git_evidence_json,a.kind,a.title,s.stage_key,pv.url feishu_url from collab_submission sub join collab_artifact_version v on v.id=sub.artifact_version_id join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where s.project_id=? and sub.status='APPROVED' and s.stage_order<? and sub.id=(select max(x.id) from collab_submission x where x.stage_id=sub.stage_id and x.status='APPROVED') order by s.stage_order",
                (rs,n)->artifactView(rs,rs.getString("stage_key"),projectKey,false),project.projectId(),current.order());
        List<ArtifactView> history=includeHistory?jdbc.query("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.git_evidence_json,a.kind,a.title,s.stage_key,pv.url feishu_url,sub.status submission_status from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id join collab_project p on p.id=s.project_id left join collab_submission sub on sub.artifact_version_id=v.id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where s.project_id=? and (sub.status='APPROVED' or p.owner_user_id=? or v.uploaded_by=? or (sub.status='PENDING_APPROVAL' and exists(select 1 from collab_approval ap where ap.submission_id=sub.id and ap.reviewer_user_id=?))) order by s.stage_order,v.attempt_no,v.version_no",(rs,n)->artifactView(rs,rs.getString("stage_key"),projectKey,"PENDING_APPROVAL".equals(rs.getString("submission_status"))),project.projectId(),actorId,actorId,actorId):List.of();
        return new ContextView(project.projectKey(),new CurrentStage(current.stageKey(),current.attemptNo(),current.status()),actorId,approved,includeHistory,history,new NextArtifact(current.artifactKind(),"[]",current.gitRequired()));
    }

    @Transactional(readOnly=true)
    public List<ArtifactView> artifacts(String projectKey,Long actorId){
        var project=authorization.requireMemberOrThrow(projectKey,actorId);
        return jdbc.query("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.git_evidence_json,a.kind,a.title,s.stage_key,pv.url feishu_url,sub.status submission_status from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id left join collab_submission sub on sub.artifact_version_id=v.id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where s.project_id=? and (sub.status='APPROVED' or (sub.status='PENDING_APPROVAL' and (exists(select 1 from collab_approval ap where ap.submission_id=sub.id and ap.reviewer_user_id=?) or v.uploaded_by=? or exists(select 1 from collab_project p where p.id=s.project_id and p.owner_user_id=?)))) order by s.stage_order,v.attempt_no,v.version_no",(rs,n)->artifactView(rs,rs.getString("stage_key"),projectKey,"PENDING_APPROVAL".equals(rs.getString("submission_status"))),project.projectId(),actorId,actorId,actorId);
    }

    @Transactional(readOnly=true)
    public ArtifactContentView content(String projectKey,Long versionId,Long actorId){
        var project=authorization.requireMemberOrThrow(projectKey,actorId);
        Map<String,Object> content=jdbc.query("select v.id,v.sha256,v.markdown_content,v.size_bytes,v.uploaded_by,a.title,s.stage_key,sub.status submission_status,p.owner_user_id,exists(select 1 from collab_approval ap where ap.submission_id=sub.id and ap.reviewer_user_id=?) is_reviewer from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id join collab_project p on p.id=s.project_id left join collab_submission sub on sub.artifact_version_id=v.id where v.id=? and p.id=? order by sub.id desc limit 1",rs->rs.next()?row(rs):null,actorId,versionId,project.projectId());
        if(content==null)throw error("COLLAB_ARTIFACT_NOT_FOUND","Artifact version not found",HttpStatus.NOT_FOUND);
        boolean allowed="APPROVED".equals(content.get("submission_status"))||actorId.equals(content.get("owner_user_id"))||actorId.equals(content.get("uploaded_by"))||"PENDING_APPROVAL".equals(content.get("submission_status"))&&Boolean.TRUE.equals(content.get("is_reviewer"));
        if(!allowed)throw error("COLLAB_ARTIFACT_FORBIDDEN","Artifact version is not available to this member",HttpStatus.FORBIDDEN);
        return new ArtifactContentView(versionId,(String)content.get("stage_key"),(String)content.get("title"),(String)content.get("sha256"),((Number)content.get("size_bytes")).longValue(),(String)content.get("markdown_content"));
    }

    @Transactional(readOnly=true)
    public List<EventView> events(String projectKey,Long actorId){var p=authorization.requireMemberOrThrow(projectKey,actorId);return jdbc.query("select e.id,e.stage_id,s.stage_key,e.attempt_no,e.event_type,e.actor_user_id,u.display_name actor_name,e.from_status,e.to_status,e.reference_id,e.created_at from collab_stage_event e left join collab_stage s on s.id=e.stage_id left join iam_user u on u.id=e.actor_user_id where e.project_id=? order by e.created_at desc,e.id desc limit 300",(rs,n)->new EventView(rs.getLong("id"),(Long)rs.getObject("stage_id"),rs.getString("stage_key"),(Integer)rs.getObject("attempt_no"),rs.getString("event_type"),(Long)rs.getObject("actor_user_id"),rs.getString("actor_name"),rs.getString("from_status"),rs.getString("to_status"),(Long)rs.getObject("reference_id"),instant(rs,"created_at")),p.projectId());}

    @Transactional(readOnly=true)
    public TaskPromptView taskPrompt(String projectKey,String stageKey,Long actorId){var p=authorization.requireMemberOrThrow(projectKey,actorId);StageRow s=readStage(p.projectId(),normalizeStage(stageKey));if(!s.ownerUserId().equals(actorId))throw error("COLLAB_STAGE_OWNER_REQUIRED","Only the current stage owner may generate its task prompt",HttpStatus.FORBIDDEN);String contextPrompt=s.order()>1?CollabStagePromptFactory.context(p.projectKey(),s.title()):null;String submitPrompt=CollabStagePromptFactory.submit(p.projectKey(),s.title(),s.id(),s.attemptNo(),s.gitRequired());return new TaskPromptView(p.projectKey(),s.stageKey(),s.attemptNo(),submitPrompt,contextPrompt);}

    private ArtifactView artifact(String projectKey,Long versionId,Long actorId){return jdbc.queryForObject("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.git_evidence_json,a.kind,a.title,s.stage_key,pv.url feishu_url from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where v.id=?",(rs,n)->artifactView(rs,rs.getString("stage_key"),projectKey,false),versionId);}
    private SubmissionView submission(String projectKey,Long id,Long actor){var p=authorization.requireMemberOrThrow(projectKey,actor);return jdbc.queryForObject("select sub.id,sub.stage_id,s.stage_key,sub.attempt_no,sub.artifact_version_id,sub.status,s.status stage_status,coalesce((select ap.state from collab_approval ap where ap.submission_id=sub.id order by ap.id limit 1),'') approval_state,sub.submitted_at,sub.decided_at from collab_submission sub join collab_stage s on s.id=sub.stage_id where sub.id=? and s.project_id=?",(rs,n)->new SubmissionView(rs.getLong("id"),rs.getLong("stage_id"),rs.getString("stage_key"),rs.getInt("attempt_no"),rs.getLong("artifact_version_id"),rs.getString("status"),rs.getString("stage_status"),rs.getString("approval_state"),"PENDING_APPROVAL".equals(rs.getString("status"))?"WAIT_REVIEW":"NEXT_STAGE",instant(rs,"submitted_at"),instant(rs,"decided_at")),id,p.projectId());}
    private StageRow lockedStage(Long projectId,String key){return jdbc.query("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no,started_at,completed_at from collab_stage where project_id=? and stage_key=? for update",rs->rs.next()?stage(rs):null,projectId,key);}
    private StageRow readStage(Long projectId,String key){StageRow stage=jdbc.query("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no,started_at,completed_at from collab_stage where project_id=? and stage_key=?",rs->rs.next()?stage(rs):null,projectId,key);if(stage==null)throw error("COLLAB_STAGE_NOT_FOUND","Stage not found",HttpStatus.NOT_FOUND);return stage;}
    private StageRow stage(ResultSet rs)throws SQLException{return new StageRow(rs.getLong("id"),rs.getString("stage_key"),rs.getInt("stage_order"),rs.getString("title"),rs.getString("artifact_kind"),rs.getBoolean("git_required"),rs.getLong("owner_user_id"),(Long)rs.getObject("reviewer_user_id"),rs.getString("status"),rs.getInt("attempt_no"),rs.getInt("version_no"),instant(rs,"started_at"),instant(rs,"completed_at"));}
    private void completeStage(Long projectId,StageRow stage,Long actorId,Long submissionId,Instant now){
        int groupStart=stageGroupStart(stage.order());
        int groupEnd=stageGroupEnd(groupStart);
        List<Integer> unfinished=jdbc.query("select stage_order from collab_stage where project_id=? and stage_order between ? and ? and status<>'COMPLETED' for update",(rs,n)->rs.getInt(1),projectId,groupStart,groupEnd);
        if(!unfinished.isEmpty())return;
        int nextOrder=groupEnd+1;
        if(nextOrder>8){jdbc.update("update collab_project set status='COMPLETED',version_no=version_no+1,time_updated=? where id=?",now,projectId);return;}
        int nextEnd=stageGroupEnd(nextOrder);
        jdbc.update("update collab_project set current_stage_order=?,version_no=version_no+1,time_updated=? where id=?",nextOrder,now,projectId);
        List<Long> nextStageIds=jdbc.query("select id from collab_stage where project_id=? and stage_order between ? and ? order by stage_order",(rs,n)->rs.getLong(1),projectId,nextOrder,nextEnd);
        for(Long nextStageId:nextStageIds){
            jdbc.update("update collab_stage set status='IN_PROGRESS',started_at=coalesce(started_at,?),time_updated=? where id=? and status='WAITING'",now,now,nextStageId);
            feishuOutbox.enqueueStageHandoff(projectId,nextStageId,submissionId);
        }
    }
    private int stageGroupStart(int order){return order==4?3:order==6?5:order;}
    private int stageGroupEnd(int start){return start==3||start==5?start+1:start;}
    private void lockProjectFlow(Long projectId){jdbc.queryForObject("select id from collab_project where id=? for update",Long.class,projectId);}
    private void addEvent(Long projectId,StageRow stage,String event,Long actor,String from,String to,Long reference,Object payload){jdbc.update("insert into collab_stage_event(time_created,time_updated,project_id,stage_id,attempt_no,event_type,actor_user_id,from_status,to_status,reference_id,payload_json,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",Instant.now(),Instant.now(),projectId,stage.id(),stage.attemptNo(),event,actor,from,to,reference,json(payload),Instant.now());}
    private List<Long> additionalReviewerIds(Long stageId){return jdbc.query("select reviewer_user_id from collab_stage_additional_reviewer where stage_id=? order by id",(rs,n)->rs.getLong(1),stageId);}
    private List<Long> reviewerIds(Long projectId,StageRow stage){List<Long> ids=new ArrayList<>();if(stage.reviewerUserId()!=null)ids.add(stage.reviewerUserId());ids.addAll(additionalReviewerIds(stage.id()));return ids.stream().distinct().toList();}
    private void validateArtifact(StageRow stage,ArtifactUpload r){if(r.title().isBlank()||r.markdownContent().isBlank())throw error("COLLAB_ARTIFACT_EMPTY","Title and Markdown content are required",HttpStatus.BAD_REQUEST);byte[] bytes=r.markdownContent().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_MARKDOWN_BYTES)throw error("COLLAB_ARTIFACT_TOO_LARGE","Markdown content must not exceed 1 MB",HttpStatus.PAYLOAD_TOO_LARGE);if(!r.markdownContent().equals(new String(bytes,StandardCharsets.UTF_8)))throw error("COLLAB_ARTIFACT_ENCODING_INVALID","Markdown must be valid UTF-8",HttpStatus.BAD_REQUEST);String actual=sha256(r.markdownContent());if(!actual.equalsIgnoreCase(r.sha256()))throw error("COLLAB_ARTIFACT_HASH_MISMATCH","SHA-256 does not match the Markdown content",HttpStatus.UNPROCESSABLE_ENTITY);if(stage.gitRequired()){if(r.gitEvidence()==null)throw error("COLLAB_GIT_EVIDENCE_REQUIRED","Repository and commit/PR URLs are required",HttpStatus.UNPROCESSABLE_ENTITY);validateGitUrl(r.gitEvidence().repositoryUrl());validateGitUrl(r.gitEvidence().commitOrPullRequestUrl());}}
    private void validateGitUrl(String value){try{java.net.URI uri=java.net.URI.create(value);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);if(!"https".equalsIgnoreCase(uri.getScheme())||!(host.equals("github.com")||host.endsWith(".github.com")||host.equals("gitlab.com")||host.endsWith(".gitlab.com"))||uri.getUserInfo()!=null)throw new IllegalArgumentException();}catch(RuntimeException ex){throw error("COLLAB_GIT_URL_INVALID","Git evidence must use a public HTTPS GitHub or GitLab URL",HttpStatus.UNPROCESSABLE_ENTITY);}}
    private void requireStageOwner(StageRow s,Long actor){if(!s.ownerUserId().equals(actor))throw error("COLLAB_STAGE_OWNER_REQUIRED","Only the current stage owner may perform this operation",HttpStatus.FORBIDDEN);}
    private void requireCurrentStage(int order,StageRow stage){boolean current=stage.order()==order||((order==3||order==5)&&stage.order()==order+1);if(!current)throw error("COLLAB_STAGE_NOT_CURRENT","Only a stage in the current project stage group can be submitted",HttpStatus.CONFLICT);}
    private String normalizeStage(String key){String k=key==null?"":key.trim().toUpperCase(Locale.ROOT);if(!STAGE_KEYS.contains(k))throw error("COLLAB_STAGE_NOT_FOUND","Stage not found",HttpStatus.NOT_FOUND);return k;}
    private String validateIdempotencyKey(String key){if(key==null||key.isBlank()||key.length()>128)throw error("IDEMPOTENCY_KEY_INVALID","Idempotency-Key must contain 1 to 128 characters",HttpStatus.BAD_REQUEST);return key.trim();}
    private boolean requiresReviewer(String level,String key){return "L1".equals(level)||"L2".equals(level)&&Set.of("PRODUCT","FRONTEND_CODING","BACKEND_CODING").contains(key);}
    private ArtifactView artifactView(Map<String,Object> r){Map<String,String> git=gitEvidence(Objects.toString(r.get("git_evidence_json"),null));return new ArtifactView(((Number)r.get("id")).longValue(),((Number)r.get("artifact_id")).longValue(),((Number)r.get("version_no")).intValue(),((Number)r.get("attempt_no")).intValue(),(String)r.get("stage_key"),(String)r.get("kind"),(String)r.get("title"),(String)r.get("sha256"),((Number)r.get("size_bytes")).longValue(),(String)r.get("url"),null,git.get("repositoryUrl"),git.get("commitOrPullRequestUrl"),"PENDING_APPROVAL".equals(r.get("submission_status")));}
    private ArtifactView artifactView(ResultSet rs,String stageKey,String projectKey,boolean pending)throws SQLException{Long id=rs.getLong("id");Map<String,String> git=gitEvidence(rs.getString("git_evidence_json"));return new ArtifactView(id,rs.getLong("artifact_id"),rs.getInt("version_no"),rs.getInt("attempt_no"),stageKey,rs.getString("kind"),rs.getString("title"),rs.getString("sha256"),rs.getLong("size_bytes"),rs.getString("feishu_url"),"/api/v1/collab-projects/"+projectKey+"/artifacts/"+id+"/content",git.get("repositoryUrl"),git.get("commitOrPullRequestUrl"),pending);}
    private Map<String,String> gitEvidence(String json){if(json==null||json.isBlank())return Map.of();try{return mapper.readValue(json,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});}catch(Exception ignored){return Map.of();}}
    private Map<String,Object> row(ResultSet rs)throws SQLException{Map<String,Object> m=new HashMap<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)m.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));return m;}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String sha256(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Instant instant(ResultSet rs,String column)throws SQLException{java.sql.Timestamp t=rs.getTimestamp(column);return t==null?null:t.toInstant();}
    private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}

    private record StageRow(Long id,String stageKey,int order,String title,String artifactKind,boolean gitRequired,Long ownerUserId,Long reviewerUserId,String status,int attemptNo,int versionNo,Instant startedAt,Instant completedAt){}
    public record WorkflowView(String projectKey,String approvalLevel,String projectStatus,int currentStageOrder,List<StageWorkflowView> stages){}
    public record StageWorkflowView(Long stageId,String stageKey,int order,String title,String artifactKind,boolean gitRequired,Long ownerUserId,Long reviewerUserId,List<Long> additionalReviewerUserIds,String status,int attemptNo,int versionNo,Long submissionId,String submissionStatus,Long artifactVersionId,Instant submittedAt,String approvalState,Long frozenReviewerUserId,Integer approvalVersionNo){}
    public record ArtifactView(Long artifactVersionId,Long artifactId,int versionNo,int attemptNo,String stageKey,String kind,String title,String sha256,long sizeBytes,String feishuUrl,String contentUrl,String repositoryUrl,String commitOrPullRequestUrl,boolean pendingApproval){}
    public record ArtifactContentView(Long artifactVersionId,String stageKey,String title,String sha256,long sizeBytes,String markdownContent){}
    public record SubmissionView(Long submissionId,Long stageId,String stageKey,int attemptNo,Long artifactVersionId,String submissionStatus,String stageStatus,String approvalState,String nextAction,Instant submittedAt,Instant decidedAt){}
    public record CurrentStage(String key,int attemptNo,String status){}
    public record NextArtifact(String kind,String requiredFields,boolean gitEvidenceRequired){}
    public record ContextView(String projectKey,CurrentStage currentStage,Long actorUserId,List<ArtifactView> approvedArtifacts,boolean historyIncluded,List<ArtifactView> history,NextArtifact nextExpectedArtifact){}
    public record EventView(Long id,Long stageId,String stageKey,Integer attemptNo,String eventType,Long actorUserId,String actorName,String fromStatus,String toStatus,Long referenceId,Instant createdAt){}
    public record TaskPromptView(String projectKey,String stageKey,int attemptNo,String prompt,String contextPrompt){}
}
