package com.company.skillplatform.collabproject.flow.application;

import com.company.skillplatform.collabproject.application.CollabProjectAuthorizationService;
import com.company.skillplatform.collabproject.application.CollabFeishuOutbox;
import com.company.skillplatform.collabproject.application.CollabStagePromptFactory;
import com.company.skillplatform.collabproject.application.CollabGitlabRootValidator;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmPackagePreparationService.PreparedPackage;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmPackageRepository;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmPackageRepository.FileRecord;
import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmPackageRepository.Manifest;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.ArtifactUpload;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.DecisionRequest;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.SubmissionRequest;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
    private static final List<List<Integer>> STAGE_GROUPS = List.of(
            List.of(1), List.of(2), List.of(3, 4), List.of(5, 6), List.of(7), List.of(8));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CollabProjectAuthorizationService authorization;
    private final CollabFeishuOutbox feishuOutbox;
    private final CollabGitlabRootValidator gitlabRootValidator;
    private final CollabAsdmPackageRepository asdmPackages;

    public CollabProjectFlowService(JdbcTemplate jdbc, ObjectMapper mapper,
            CollabProjectAuthorizationService authorization, CollabFeishuOutbox feishuOutbox,
            CollabGitlabRootValidator gitlabRootValidator) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.authorization = authorization;
        this.feishuOutbox = feishuOutbox;
        this.gitlabRootValidator = gitlabRootValidator;
        this.asdmPackages = new CollabAsdmPackageRepository(jdbc);
    }

    @Transactional(readOnly = true)
    public WorkflowView workflow(String projectKey, Long actorId) {
        var project = authorization.requireMemberOrThrow(projectKey, actorId);
        List<StageWorkflowView> stages = jdbc.query("select s.id,s.stage_key,s.stage_order,s.title,s.artifact_kind,s.git_required,s.owner_user_id,s.reviewer_user_id,s.status,s.attempt_no,s.version_no,sub.id submission_id,sub.status submission_status,sub.artifact_version_id,sub.submitted_at,ap.state approval_state,ap.reviewer_user_id frozen_reviewer_id,ap.version_no approval_version_no from collab_stage s left join collab_submission sub on sub.id=(select max(s2.id) from collab_submission s2 where s2.stage_id=s.id) left join collab_approval ap on ap.id=(select a0.id from collab_approval a0 where a0.submission_id=sub.id and a0.reviewer_user_id=? order by a0.id desc limit 1) where s.project_id=? order by s.stage_order",
                (rs,n)->new StageWorkflowView(rs.getLong("id"),rs.getString("stage_key"),rs.getInt("stage_order"),rs.getString("title"),rs.getString("artifact_kind"),rs.getBoolean("git_required"),rs.getLong("owner_user_id"),(Long)rs.getObject("reviewer_user_id"),additionalReviewerIds(rs.getLong("id")),rs.getString("status"),rs.getInt("attempt_no"),rs.getInt("version_no"),(Long)rs.getObject("submission_id"),rs.getString("submission_status"),(Long)rs.getObject("artifact_version_id"),instant(rs,"submitted_at"),rs.getString("approval_state"),(Long)rs.getObject("frozen_reviewer_id"),(Integer)rs.getObject("approval_version_no"),stageRoleName(rs.getLong("id"),"OWNER"),stageRoleName(rs.getLong("id"),"REVIEWER"),pendingReviewerStateIds((Long)rs.getObject("submission_id")),reviewerStateIds((Long)rs.getObject("submission_id"),"APPROVED"),reviewerRoleProgress((Long)rs.getObject("submission_id"))),actorId,project.projectId());
        return new WorkflowView(project.projectKey(),project.approvalLevel(),project.status(),project.currentStageOrder(),stages);
    }

    @Transactional
    public ArtifactView upload(String projectKey, String stageKey, ArtifactUpload request, String idempotencyKey, Long actorId) {
        idempotencyKey=validateIdempotencyKey(idempotencyKey);
        var project = authorization.requireMemberOrThrow(projectKey, actorId);
        authorization.requireWritable(project);
        lockProjectFlow(project.projectId());
        requireWikiRootConfigured(project.projectId());
        String key = normalizeStage(stageKey);
        StageRow stage = lockedStage(project.projectId(), key);
        if (!Set.of("WAITING", "IN_PROGRESS", "REWORK").contains(stage.status()))
            throw error("COLLAB_STAGE_NOT_SUBMITTABLE", "Skipped or completed stages do not accept artifacts", HttpStatus.CONFLICT);
        if (request.stageId() == null || !request.stageId().equals(stage.id()) || request.attemptNo() < 1)
            throw error("COLLAB_STAGE_VERSION_STALE", "Stage or attempt no longer matches", HttpStatus.CONFLICT);
        String kind = request.kind().trim().toUpperCase(Locale.ROOT);
        if (!stage.artifactKind().equals(kind)) throw error("COLLAB_ARTIFACT_KIND_INVALID", "Artifact kind does not match this stage", HttpStatus.UNPROCESSABLE_ENTITY);
        requireStageOwner(project, stage, actorId);
        validateArtifact(project, stage, request);
        String fingerprint = sha256(json(Map.of("stageId",stage.id(),"attemptNo",request.attemptNo(),"kind",kind,"title",request.title().trim(),"sha256",request.sha256().toLowerCase(Locale.ROOT),"content",request.markdownContent(),"gitEvidence",request.gitEvidence()==null?Map.of():request.gitEvidence())));
        Map<String,Object> existing = jdbc.query("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.request_fingerprint,v.uploaded_by,a.kind,a.title,s.stage_key,pv.url from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where v.stage_id=? and v.upload_idempotency_key=?", rs -> rs.next()?row(rs):null, stage.id(), idempotencyKey);
        if (existing != null) {
            if (!Objects.equals(((Number)existing.get("uploaded_by")).longValue(), actorId)) throw error("IDEMPOTENCY_KEY_REUSED", "Idempotency key belongs to another uploader", HttpStatus.CONFLICT);
            if (!fingerprint.equals(existing.get("request_fingerprint"))) throw error("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for different content", HttpStatus.CONFLICT);
            return artifactView(existing);
        }
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
        var project=authorization.requireMemberOrThrow(projectKey,actorId); authorization.requireWritable(project); String key=normalizeStage(stageKey);
        lockProjectFlow(project.projectId());
        requireWikiRootConfigured(project.projectId());
        StageRow stage=lockedStage(project.projectId(),key);
        if (!Objects.equals(stage.id(),request.stageId()) || request.attemptNo()<1) throw error("COLLAB_STAGE_VERSION_STALE","Stage or attempt no longer matches",HttpStatus.CONFLICT);
        String fingerprint=sha256(json(Map.of("stageId",stage.id(),"attemptNo",request.attemptNo(),"artifactVersionId",request.artifactVersionId(),"expectedStageVersion",request.expectedStageVersion())));
        Map<String,Object> prior=jdbc.query("select id,submitted_by,request_fingerprint from collab_submission where stage_id=? and idempotency_key=?",rs->rs.next()?Map.of("id",rs.getLong("id"),"submitted_by",rs.getLong("submitted_by"),"request_fingerprint",rs.getString("request_fingerprint")):null,stage.id(),idempotencyKey);
        if(prior!=null){if(!Objects.equals(((Number)prior.get("submitted_by")).longValue(),actorId)||!fingerprint.equals(prior.get("request_fingerprint")))throw error("IDEMPOTENCY_KEY_REUSED","Idempotency key was already used by another actor or for a different submission",HttpStatus.CONFLICT);return submission(projectKey,(Long)prior.get("id"),actorId);}
        requireUnclaimedAttempt(stage);
        requireStageOwner(project,stage,actorId); requireCurrentStage(project.currentStageOrder(),stage);
        if(stage.attemptNo()!=request.attemptNo()) throw error("COLLAB_STAGE_VERSION_STALE","Stage or attempt no longer matches",HttpStatus.CONFLICT);
        if(stage.versionNo()!=request.expectedStageVersion()) throw error("COLLAB_STAGE_VERSION_STALE","Stage changed; reload and retry",HttpStatus.CONFLICT);
        if(!Set.of("WAITING","IN_PROGRESS","REWORK").contains(stage.status()))throw error("COLLAB_STAGE_NOT_SUBMITTABLE","Stage is not accepting submissions",HttpStatus.CONFLICT);
        Map<String,Object> artifact=jdbc.query("select v.id,v.stage_id,v.attempt_no,v.artifact_id,a.kind,a.title,v.sha256 from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id where v.id=?",rs->rs.next()?Map.of("id",rs.getLong("id"),"stage_id",rs.getLong("stage_id"),"attempt_no",rs.getInt("attempt_no"),"artifact_id",rs.getLong("artifact_id"),"kind",rs.getString("kind"),"title",rs.getString("title"),"sha256",rs.getString("sha256")):null,request.artifactVersionId());
        if(artifact==null||!Objects.equals(((Number)artifact.get("stage_id")).longValue(),stage.id())||((Number)artifact.get("attempt_no")).intValue()!=stage.attemptNo())throw error("COLLAB_ARTIFACT_NOT_CURRENT","Artifact version does not belong to the current stage attempt",HttpStatus.UNPROCESSABLE_ENTITY);
        Long submissionId=claimSubmission(project,stage,request.artifactVersionId(),idempotencyKey,fingerprint,actorId);
        return submission(projectKey,submissionId,actorId);
    }

    private Long claimSubmission(CollabProjectAuthorizationService.ProjectRef project,StageRow stage,Long artifactVersionId,String idempotencyKey,String fingerprint,Long actorId) {
        requireUnclaimedAttempt(stage);
        if (!Set.of("WAITING", "IN_PROGRESS", "REWORK").contains(stage.status()))
            throw error("ASDM_STAGE_NOT_ACTIVE","Stage is not accepting submissions",HttpStatus.CONFLICT);
        List<ReviewerRoleRoster> roleRosters=project.groupId()!=null?reviewerRoleRosters(stage.id()):List.of();
        boolean manual=project.groupId()==null?requiresReviewer(project.approvalLevel(),stage.stageKey()):
                "L1".equals(project.approvalLevel())||"L2".equals(project.approvalLevel())&&
                        (roleRosters.stream().anyMatch(roster->!roster.userIds().isEmpty())||roleRosters.isEmpty()&&!reviewerIds(project,stage).isEmpty());
        if(manual&&project.groupId()!=null&&roleRosters.isEmpty()) {
            Integer boundRoles=jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role where stage_id=?",Integer.class,stage.id());
            if(boundRoles!=null&&boundRoles>0) throw error("COLLAB_REVIEWER_UNAVAILABLE","Reviewer role members have not been configured for this stage",HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if(manual&&project.groupId()!=null&&!roleRosters.isEmpty()) for(ReviewerRoleRoster roster:roleRosters)
            if(roster.userIds().isEmpty()) throw error("COLLAB_REVIEWER_UNAVAILABLE","Every bound reviewer role needs at least one active reviewer",HttpStatus.UNPROCESSABLE_ENTITY);
        List<Long> reviewers=roleRosters.isEmpty()?reviewerIds(project,stage):roleRosters.stream().flatMap(r->r.userIds().stream()).distinct().sorted().toList();
        if(manual&&reviewers.isEmpty())throw error("COLLAB_REVIEWER_UNAVAILABLE","At least one active reviewer is required",HttpStatus.UNPROCESSABLE_ENTITY);
        if(manual)for(Long reviewerId:reviewers)authorization.requireActiveMember(project.projectId(),reviewerId);
        Instant now=Instant.now();
        int updated=jdbc.update("update collab_stage set status='PUBLISHING',version_no=version_no+1,started_at=coalesce(started_at,?),time_updated=? where id=? and version_no=? and status in ('WAITING','IN_PROGRESS','REWORK')",now,now,stage.id(),stage.versionNo());
        if(updated!=1)throw error("STAGE_ATTEMPT_ALREADY_CLAIMED","Stage attempt was claimed by another submission",HttpStatus.CONFLICT);
        List<Map<String,Object>> roleSnapshots=new ArrayList<>();
        for(ReviewerRoleRoster roster:roleRosters)roleSnapshots.add(Map.of("roleId",roster.roleId(),"roleKey",roster.roleKey(),"roleName",roster.roleName(),"userIds",roster.userIds()));
        Map<String,Object> policy=Map.of("approvalLevel",project.approvalLevel(),"approvalMode",manual?"MANUAL":"AUTOMATIC","reviewerUserIds",manual?reviewers:List.of(),"reviewerRoles",roleSnapshots);
        jdbc.update("insert into collab_submission(time_created,time_updated,stage_id,attempt_no,artifact_version_id,submitted_by,policy_snapshot,status,idempotency_key,request_fingerprint,submitted_at,decided_at,publication_gate_state) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",now,now,stage.id(),stage.attemptNo(),artifactVersionId,actorId,json(policy),"PUBLISHING",idempotencyKey,fingerprint,now,null,"WAITING_FEISHU");
        Long submissionId=jdbc.queryForObject("select id from collab_submission where stage_id=? and idempotency_key=?",Long.class,stage.id(),idempotencyKey);
        feishuOutbox.enqueuePublish(project.projectId(),artifactVersionId);
        if(!idempotencyKey.startsWith("asdm:"))
            jdbc.update("update collab_asdm_candidate set status='CLOSED_BY_SKILL',time_updated=? where stage_id=? and attempt_no=? and status in ('DEBOUNCING','NOTIFIED','CONFIRM_REQUESTED')",now,stage.id(),stage.attemptNo());
        addEvent(project.projectId(),stage,"SUBMITTED",actorId,stage.status(),"PUBLISHING",submissionId,Map.of("artifactVersionId",artifactVersionId,"approvalMode",manual?"MANUAL":"AUTOMATIC","source",idempotencyKey.startsWith("asdm:")?"ASDM_GITLAB":"SKILL"));
        return submissionId;
    }

    private void requireUnclaimedAttempt(StageRow stage) {
        Integer claimed=jdbc.queryForObject("select count(*) from collab_submission where stage_id=? and attempt_no=?",Integer.class,stage.id(),stage.attemptNo());
        if(claimed!=null&&claimed>0)throw error("STAGE_ATTEMPT_ALREADY_CLAIMED","Stage attempt was claimed by another submission",HttpStatus.CONFLICT);
    }

    /** Called only after the ASDM worker has rechecked the remote file against the pinned snapshot. */
    @Transactional
    public SubmissionView publishAsdmCandidate(String requirementKey,long candidateId,int revision,long actorId) {
        var project=authorization.requireMemberOrThrow(requirementKey,actorId);
        authorization.requireWritable(project);
        if(project.groupId()==null)throw error("ASDM_STAGE_NOT_ACTIVE","ASDM requires a project group",HttpStatus.CONFLICT);
        lockProjectFlow(project.projectId());
        requireWikiRootConfigured(project.projectId());
        StageRow stage=lockedStage(project.projectId(),"REQUIREMENT");
        requireStageOwner(project,stage,actorId);
        requireCurrentStage(project.currentStageOrder(),stage);
        Map<String,Object> candidate=jdbc.query("select c.id,c.attempt_no,c.detected_revision,c.status,c.source_path,c.source_commit_sha,c.source_blob_sha,c.content_sha256,c.markdown_snapshot,c.confirmed_by,c.submission_id,p.asdm_branch,p.asdm_requirement_path,g.asdm_gitlab_project_id,p.name requirement_name "
                +"from collab_asdm_candidate c join collab_project p on p.id=c.requirement_id join collab_group g on g.id=p.group_id where c.id=? and c.requirement_id=? and c.stage_id=? for update",
                rs->rs.next()?row(rs):null,candidateId,project.projectId(),stage.id());
        if(candidate!=null&&"CLAIMED".equals(candidate.get("status"))&&((Number)candidate.get("detected_revision")).intValue()==revision&&candidate.get("submission_id")!=null&&Objects.equals(((Number)candidate.get("confirmed_by")).longValue(),actorId))
            return submission(requirementKey,((Number)candidate.get("submission_id")).longValue(),actorId);
        if(candidate==null||!"CONFIRM_REQUESTED".equals(candidate.get("status"))||((Number)candidate.get("detected_revision")).intValue()!=revision)
            throw error("ASDM_CANDIDATE_STALE","ASDM candidate is no longer current",HttpStatus.CONFLICT);
        if(!Set.of("IN_PROGRESS","REWORK").contains(stage.status()))throw error("ASDM_STAGE_NOT_ACTIVE","Requirement stage is not active",HttpStatus.CONFLICT);
        requireUnclaimedAttempt(stage);
        if(((Number)candidate.get("attempt_no")).intValue()!=stage.attemptNo()||!Objects.equals(((Number)candidate.get("confirmed_by")).longValue(),actorId))
            throw error("ASDM_CANDIDATE_STALE","ASDM candidate no longer matches this attempt",HttpStatus.CONFLICT);
        String markdown=(String)candidate.get("markdown_snapshot");
        String path=(String)candidate.get("source_path");
        String directory=(String)candidate.get("asdm_requirement_path");
        if(markdown==null||markdown.isBlank()||markdown.getBytes(StandardCharsets.UTF_8).length>MAX_MARKDOWN_BYTES||
                directory==null||!path.equals(directory+"/requirement-doc.md")||!sha256(markdown).equalsIgnoreCase((String)candidate.get("content_sha256")))
            throw error("ASDM_FILE_INVALID","ASDM candidate snapshot is invalid",HttpStatus.UNPROCESSABLE_ENTITY);
        String idempotencyKey="asdm:"+candidateId+":"+revision;
        Map<String,Object> source=Map.of("gitlabProjectId",candidate.get("asdm_gitlab_project_id"),"branch",candidate.get("asdm_branch"),
                "directory",directory,"filePath",path,"commitSha",candidate.get("source_commit_sha"),
                "blobSha",candidate.get("source_blob_sha"),"contentSha256",candidate.get("content_sha256"));
        String fingerprint=sha256(json(source));
        Instant now=Instant.now();
        String title=(String)candidate.get("requirement_name")+" 需求分析";
        Long artifactId=jdbc.query("select id from collab_artifact where stage_id=? and kind=? order by id limit 1",rs->rs.next()?rs.getLong(1):null,stage.id(),stage.artifactKind());
        if(artifactId==null){
            jdbc.update("insert into collab_artifact(time_created,time_updated,project_id,stage_id,kind,title,status) values(?,?,?,?,?,?,'ACTIVE')",now,now,project.projectId(),stage.id(),stage.artifactKind(),title);
            artifactId=jdbc.queryForObject("select id from collab_artifact where stage_id=? and kind=? order by id desc limit 1",Long.class,stage.id(),stage.artifactKind());
        }
        int version=jdbc.queryForObject("select coalesce(max(version_no),0)+1 from collab_artifact_version where stage_id=?",Integer.class,stage.id());
        jdbc.update("insert into collab_artifact_version(time_created,time_updated,artifact_id,stage_id,version_no,attempt_no,sha256,mime_type,size_bytes,markdown_content,uploaded_by,upload_idempotency_key,request_fingerprint,source_type,source_ref_json) values(?,?,?,?,?,?,?,'text/markdown; charset=utf-8',?,?,?,?,?,'ASDM_GITLAB',?)",
                now,now,artifactId,stage.id(),version,stage.attemptNo(),candidate.get("content_sha256"),markdown.getBytes(StandardCharsets.UTF_8).length,markdown,actorId,idempotencyKey,fingerprint,json(source));
        Long artifactVersionId=jdbc.queryForObject("select id from collab_artifact_version where stage_id=? and upload_idempotency_key=?",Long.class,stage.id(),idempotencyKey);
        jdbc.update("update collab_artifact set title=?,latest_version_id=?,time_updated=? where id=?",title,artifactVersionId,now,artifactId);
        Long submissionId=claimSubmission(project,stage,artifactVersionId,idempotencyKey,fingerprint,actorId);
        jdbc.update("update collab_asdm_candidate set status='CLAIMED',submission_id=?,time_updated=? where id=?",submissionId,now,candidateId);
        return submission(requirementKey,submissionId,actorId);
    }

    /** Claims a prepared multi-file candidate with the same stage/attempt uniqueness as Skill submissions. */
    @Transactional
    public SubmissionView publishAsdmPackage(PreparedPackage prepared) {
        var project=authorization.requireMemberOrThrow(prepared.requirementKey(),prepared.confirmedBy());
        authorization.requireWritable(project);
        if(project.groupId()==null || project.projectId()!=prepared.requirementId())
            throw error("ASDM_CANDIDATE_STALE","Candidate does not belong to this requirement",HttpStatus.CONFLICT);
        lockProjectFlow(project.projectId());
        requireWikiRootConfigured(project.projectId());
        StageRow stage=lockedStage(project.projectId(),prepared.stageKey());
        requireStageOwner(project,stage,prepared.confirmedBy());
        requireCurrentStage(project.currentStageOrder(),stage);
        if(stage.id()!=prepared.stageId() || stage.attemptNo()!=prepared.attemptNo()
                || !Set.of("IN_PROGRESS","REWORK").contains(stage.status()))
            throw error("ASDM_CANDIDATE_STALE","Stage attempt changed before package claim",HttpStatus.CONFLICT);
        requireUnclaimedAttempt(stage);
        Map<String,Object> candidate=jdbc.query("select id,status,detected_revision,attempt_no,stage_key,manifest_hash,source_commit_sha,confirmed_by from collab_asdm_candidate where id=? and requirement_id=? and stage_id=? for update",
                rs->rs.next()?row(rs):null,prepared.candidateId(),project.projectId(),stage.id());
        if(candidate==null || !"CONFIRM_REQUESTED".equals(candidate.get("status"))
                || ((Number)candidate.get("detected_revision")).intValue()!=prepared.revision()
                || ((Number)candidate.get("attempt_no")).intValue()!=prepared.attemptNo()
                || !Objects.equals(candidate.get("stage_key"),prepared.stageKey())
                || !Objects.equals(candidate.get("manifest_hash"),prepared.manifestHash())
                || !Objects.equals(candidate.get("source_commit_sha"),prepared.commitSha())
                || ((Number)candidate.get("confirmed_by")).longValue()!=prepared.confirmedBy())
            throw error("ASDM_CANDIDATE_STALE","Candidate changed before package claim",HttpStatus.CONFLICT);
        String idempotencyKey="asdm:package:"+prepared.candidateId()+":"+prepared.revision();
        String title=jdbc.queryForObject("select name from collab_project where id=?",String.class,project.projectId())+" "+stage.title();
        StringBuilder index=new StringBuilder("# ").append(title).append("\n\n")
                .append("来源 commit: `").append(prepared.commitSha()).append("`\n\n")
                .append("以下文件正在逐项发布到飞书；全部完成后本索引会更新链接。\n\n");
        for(var file:prepared.files())index.append("- ").append(file.file().sourcePath())
                .append(" (sha256: ").append(file.sha256()==null?"LINK_ONLY":file.sha256()).append(")\n");
        String markdown=index.toString();
        if(markdown.getBytes(StandardCharsets.UTF_8).length>MAX_MARKDOWN_BYTES)
            throw error("ASDM_FILE_OVERSIZE","Package index exceeds Markdown limit",HttpStatus.PAYLOAD_TOO_LARGE);
        Instant now=Instant.now();
        Long artifactId=jdbc.query("select id from collab_artifact where stage_id=? and kind=? order by id limit 1",rs->rs.next()?rs.getLong(1):null,stage.id(),stage.artifactKind());
        if(artifactId==null){
            jdbc.update("insert into collab_artifact(time_created,time_updated,project_id,stage_id,kind,title,status) values(?,?,?,?,?,?,'ACTIVE')",now,now,project.projectId(),stage.id(),stage.artifactKind(),title);
            artifactId=jdbc.queryForObject("select id from collab_artifact where stage_id=? and kind=? order by id desc limit 1",Long.class,stage.id(),stage.artifactKind());
        }
        int version=jdbc.queryForObject("select coalesce(max(version_no),0)+1 from collab_artifact_version where stage_id=?",Integer.class,stage.id());
        String fingerprint=sha256(prepared.manifestHash()+":"+prepared.commitSha());
        Map<String,Object> source=new LinkedHashMap<>();
        source.put("stageKey",prepared.stageKey());source.put("sourceCommitSha",prepared.commitSha());
        source.put("manifestHash",prepared.manifestHash());source.put("fileCount",prepared.files().size());
        jdbc.update("insert into collab_artifact_version(time_created,time_updated,artifact_id,stage_id,version_no,attempt_no,sha256,mime_type,size_bytes,markdown_content,uploaded_by,upload_idempotency_key,request_fingerprint,source_type,source_ref_json) values(?,?,?,?,?,?,?,'text/markdown; charset=utf-8',?,?,?,?,?,'ASDM_GITLAB',?)",
                now,now,artifactId,stage.id(),version,stage.attemptNo(),sha256(markdown),markdown.getBytes(StandardCharsets.UTF_8).length,markdown,prepared.confirmedBy(),idempotencyKey,fingerprint,json(source));
        Long artifactVersionId=jdbc.queryForObject("select id from collab_artifact_version where stage_id=? and upload_idempotency_key=?",Long.class,stage.id(),idempotencyKey);
        jdbc.update("update collab_artifact set title=?,latest_version_id=?,time_updated=? where id=?",title,artifactVersionId,now,artifactId);
        Long submissionId=claimSubmission(project,stage,artifactVersionId,idempotencyKey,fingerprint,prepared.confirmedBy());
        long packageId=asdmPackages.createPackage(new Manifest(project.projectId(),stage.id(),stage.attemptNo(),prepared.candidateId(),
                prepared.revision(),submissionId,prepared.commitSha(),prepared.manifestHash(),"v3.0.1","PUBLISHING_FILES",
                artifactVersionId,prepared.confirmedBy(),now));
        List<FileRecord> files=new ArrayList<>();
        for(var file:prepared.files()){
            var c=file.file();
            files.add(new FileRecord(packageId,sha256(c.sourcePath()),c.sourcePath(),file.sha256(),c.sourceBlobSha(),
                    file.sizeBytes(),c.mediaType(),c.association(),c.associationScore(),c.reasonJson(),c.deliveryMode(),
                    true,file.storageKey(),c.sourceGitUrl()));
        }
        asdmPackages.addFiles(packageId,files);
        source.put("packageId",packageId);
        jdbc.update("update collab_artifact_version set source_ref_json=?,time_updated=now(3) where id=?",json(source),artifactVersionId);
        jdbc.update("update collab_asdm_candidate set status='CLAIMED',submission_id=?,time_updated=? where id=?",submissionId,now,prepared.candidateId());
        addEvent(project.projectId(),stage,"ASDM_PACKAGE_CLAIMED",prepared.confirmedBy(),stage.status(),"PUBLISHING",packageId,
                Map.of("packageId",packageId,"fileCount",files.size(),"manifestHash",prepared.manifestHash()));
        return submission(prepared.requirementKey(),submissionId,prepared.confirmedBy());
    }

    /** Finalizes one new submission after its Wiki document has been durably published. */
    @Transactional
    public void onFeishuPublicationSucceeded(long artifactVersionId) {
        finalizeFeishuPublication(artifactVersionId,false);
    }

    /** The package worker calls this only after every selected file and the index are published. */
    @Transactional
    public void onAsdmPackagePublished(long packageId) {
        Manifest manifest=asdmPackages.findPackage(packageId).orElseThrow(()->new IllegalArgumentException("ASDM_PACKAGE_NOT_FOUND"));
        if(!asdmPackages.progress(packageId).allRequiredPublished())
            throw new IllegalStateException("ASDM package still has unpublished files");
        String indexStatus=jdbc.queryForObject("select publish_status from collab_feishu_publication where artifact_version_id=?",String.class,
                manifest.sourceIndexArtifactVersionId());
        if(!"PUBLISHED".equals(indexStatus))throw new IllegalStateException("ASDM package index is not published");
        asdmPackages.markPackageStatus(packageId,"PUBLISHED",null);
        finalizeFeishuPublication(manifest.sourceIndexArtifactVersionId(),true);
    }

    private void finalizeFeishuPublication(long artifactVersionId,boolean packageReady) {
        if(!packageReady){
            Integer packageCount=jdbc.queryForObject("select count(*) from collab_asdm_package where source_index_artifact_version_id=?",Integer.class,artifactVersionId);
            if(packageCount!=null&&packageCount>0)return;
        }
        Long projectId=jdbc.query("select s.project_id from collab_submission sub join collab_stage s on s.id=sub.stage_id where sub.artifact_version_id=?",rs->rs.next()?rs.getLong(1):null,artifactVersionId);
        if(projectId==null)return;
        lockProjectFlow(projectId);
        Map<String,Object> submission=jdbc.query("select sub.id,sub.stage_id,sub.submitted_by,sub.policy_snapshot,sub.status,sub.publication_gate_state,s.stage_key,pv.publish_status "
                +"from collab_submission sub join collab_stage s on s.id=sub.stage_id join collab_feishu_publication pv on pv.artifact_version_id=sub.artifact_version_id "
                +"where sub.artifact_version_id=? and s.project_id=? for update",rs->rs.next()?row(rs):null,artifactVersionId,projectId);
        if(submission==null||"FEISHU_PUBLISHED".equals(submission.get("publication_gate_state"))||"LEGACY_DONE".equals(submission.get("publication_gate_state")))return;
        if(!"WAITING_FEISHU".equals(submission.get("publication_gate_state"))||!"PUBLISHING".equals(submission.get("status"))||!"PUBLISHED".equals(submission.get("publish_status")))
            throw new IllegalStateException("ASDM publication gate is not ready for finalization");
        StageRow stage=lockedStage(projectId,(String)submission.get("stage_key"));
        if(!"PUBLISHING".equals(stage.status()))throw new IllegalStateException("Stage changed before Feishu publication was finalized");
        JsonNode policy;
        try{policy=mapper.readTree(String.valueOf(submission.get("policy_snapshot")));}
        catch(JsonProcessingException invalid){throw new IllegalStateException("Invalid frozen approval policy",invalid);}
        boolean manual="MANUAL".equals(policy.path("approvalMode").asText());
        long submissionId=((Number)submission.get("id")).longValue();
        long actorId=((Number)submission.get("submitted_by")).longValue();
        Instant now=Instant.now();
        if(manual){
            Map<Long,Long> approvalIds=new HashMap<>();
            for(JsonNode reviewer:policy.path("reviewerUserIds")){
                long reviewerId=reviewer.asLong();
                jdbc.update("insert into collab_approval(time_created,time_updated,submission_id,reviewer_user_id,state,version_no) values(?,?,?,?,'PENDING',0)",now,now,submissionId,reviewerId);
                approvalIds.put(reviewerId,jdbc.queryForObject("select id from collab_approval where submission_id=? and reviewer_user_id=?",Long.class,submissionId,reviewerId));
            }
            for(JsonNode role:policy.path("reviewerRoles")){
                long roleId=role.path("roleId").asLong();
                jdbc.update("insert into collab_submission_reviewer_role(submission_id,requirement_role_id,role_key_snapshot,role_name_snapshot) values(?,?,?,?)",submissionId,roleId,role.path("roleKey").asText(),role.path("roleName").asText());
                for(JsonNode reviewer:role.path("userIds")){
                    long reviewerId=reviewer.asLong();
                    jdbc.update("insert into collab_submission_reviewer_assignment(submission_id,requirement_role_id,reviewer_user_id,approval_id) values(?,?,?,?)",submissionId,roleId,reviewerId,approvalIds.get(reviewerId));
                }
            }
            jdbc.update("update collab_submission set status='PENDING_APPROVAL',publication_gate_state='FEISHU_PUBLISHED',time_updated=? where id=? and publication_gate_state='WAITING_FEISHU'",now,submissionId);
            jdbc.update("update collab_stage set status='PENDING_APPROVAL',version_no=version_no+1,time_updated=? where id=? and status='PUBLISHING'",now,stage.id());
            feishuOutbox.enqueueApprovalReminder(projectId,submissionId);
        }else{
            jdbc.update("update collab_submission set status='APPROVED',publication_gate_state='FEISHU_PUBLISHED',decided_at=?,time_updated=? where id=? and publication_gate_state='WAITING_FEISHU'",now,now,submissionId);
            jdbc.update("update collab_stage set status='COMPLETED',completed_at=?,version_no=version_no+1,time_updated=? where id=? and status='PUBLISHING'",now,now,stage.id());
            completeStage(projectId,stage,actorId,submissionId,now);
        }
        addEvent(projectId,stage,"FEISHU_PUBLISHED",actorId,"PUBLISHING",manual?"PENDING_APPROVAL":"COMPLETED",submissionId,Map.of("artifactVersionId",artifactVersionId));
    }

    @Transactional
    public SubmissionView retryFeishuPublication(String requirementKey,long submissionId,long actorId) {
        var project=authorization.requireRequirementManager(requirementKey,actorId);
        authorization.requireWritable(project);
        lockProjectFlow(project.projectId());
        Map<String,Object> row=jdbc.query("select sub.id,sub.artifact_version_id,sub.status,sub.publication_gate_state,pv.publish_status "
                +"from collab_submission sub join collab_stage s on s.id=sub.stage_id "
                +"join collab_feishu_publication pv on pv.artifact_version_id=sub.artifact_version_id "
                +"where sub.id=? and s.project_id=? for update",rs->rs.next()?row(rs):null,submissionId,project.projectId());
        if(row==null)throw error("COLLAB_SUBMISSION_NOT_FOUND","Submission not found",HttpStatus.NOT_FOUND);
        if(!"PUBLISHING".equals(row.get("status"))||!"WAITING_FEISHU".equals(row.get("publication_gate_state")))
            throw error("COLLAB_SUBMISSION_ALREADY_DECIDED","Submission is no longer awaiting publication",HttpStatus.CONFLICT);
        long artifactVersionId=((Number)row.get("artifact_version_id")).longValue();
        if("RECONCILE_REQUIRED".equals(row.get("publish_status")))
            throw error("COLLAB_FEISHU_RECONCILE_REQUIRED","Verify the existing Feishu document before retrying publication",HttpStatus.CONFLICT);
        if("PUBLISHED".equals(row.get("publish_status"))){
            onFeishuPublicationSucceeded(artifactVersionId);
        }else{
            jdbc.update("update collab_outbox set status='PENDING',attempts=0,next_attempt_at=NOW(3),last_error=NULL,time_updated=NOW(3) where event_type='PUBLISH_ARTIFACT' and aggregate_id=? and project_id=? and status in ('PENDING','FAILED')",artifactVersionId,project.projectId());
        }
        return submission(requirementKey,submissionId,actorId);
    }

    @Transactional
    public SubmissionView decide(String projectKey,Long submissionId,DecisionRequest request,Long actorId){
        var project=authorization.requireMemberOrThrow(projectKey,actorId);
        authorization.requireWritable(project);
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
        Integer roleCount=jdbc.queryForObject("select count(*) from collab_submission_reviewer_role where submission_id=?",Integer.class,submissionId);
        boolean roleBased=roleCount!=null&&roleCount>0;
        // Rejection remains immediately terminal, independent of role progress.
        boolean allRolesSatisfied=roleBased&&Boolean.TRUE.equals(jdbc.queryForObject("select not exists (select 1 from collab_submission_reviewer_role r where r.submission_id=? and not exists (select 1 from collab_submission_reviewer_assignment a join collab_approval ap on ap.id=a.approval_id where a.submission_id=r.submission_id and a.requirement_role_id=r.requirement_role_id and ap.state='APPROVED'))",Boolean.class,submissionId));
        Integer remaining=jdbc.queryForObject("select count(*) from collab_approval where submission_id=? and state='PENDING'",Integer.class,submissionId);
        boolean allApproved=!rejected&&(roleBased?allRolesSatisfied:remaining==null||remaining==0);
        if(!rejected&&roleBased) {
            List<Long> satisfiedRoleApprovalIds=jdbc.query("select ap.id from collab_approval ap where ap.submission_id=? and ap.state='PENDING' and not exists (select 1 from collab_submission_reviewer_assignment a join collab_submission_reviewer_role r on r.submission_id=a.submission_id and r.requirement_role_id=a.requirement_role_id where a.approval_id=ap.id and not exists (select 1 from collab_submission_reviewer_assignment aa join collab_approval approved on approved.id=aa.approval_id where aa.submission_id=a.submission_id and aa.requirement_role_id=a.requirement_role_id and approved.state='APPROVED'))",(rs,n)->rs.getLong(1),submissionId);
            for(Long approvalId:satisfiedRoleApprovalIds) jdbc.update("update collab_approval set state='CANCELLED',time_updated=? where id=? and state='PENDING'",now,approvalId);
        }
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
        StageRow current=readStage(project.projectId(),key); boolean mayReadHistory=Objects.equals(project.ownerUserId(),actorId)||Objects.equals(project.groupOwnerUserId(),actorId)||stageActorIds(project,current,"OWNER",false).contains(actorId)||stageActorIds(project,current,"REVIEWER",false).contains(actorId);
        if(includeHistory&&!mayReadHistory)throw error("COLLAB_HISTORY_FORBIDDEN","History is limited to the project owner and current stage assignees",HttpStatus.FORBIDDEN);
        List<ArtifactView> approved=jdbc.query("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.git_evidence_json,a.kind,a.title,s.stage_key,pv.url feishu_url from collab_submission sub join collab_artifact_version v on v.id=sub.artifact_version_id join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where s.project_id=? and sub.status='APPROVED' and s.stage_order<? and sub.id=(select max(x.id) from collab_submission x where x.stage_id=sub.stage_id and x.status='APPROVED') order by s.stage_order",
                (rs,n)->artifactView(rs,rs.getString("stage_key"),projectKey,false),project.projectId(),current.order());
        List<ArtifactView> history=includeHistory?jdbc.query("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.git_evidence_json,a.kind,a.title,s.stage_key,pv.url feishu_url,sub.status submission_status from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id join collab_project p on p.id=s.project_id left join collab_submission sub on sub.artifact_version_id=v.id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where s.project_id=? and (sub.status='APPROVED' or p.owner_user_id=? or v.uploaded_by=? or (sub.status='PENDING_APPROVAL' and exists(select 1 from collab_approval ap where ap.submission_id=sub.id and ap.reviewer_user_id=?))) order by s.stage_order,v.attempt_no,v.version_no",(rs,n)->artifactView(rs,rs.getString("stage_key"),projectKey,"PENDING_APPROVAL".equals(rs.getString("submission_status"))),project.projectId(),actorId,actorId,actorId):List.of();
        Map<String,Object> names=jdbc.queryForMap("select p.name requirement_name,g.name group_name from collab_project p join collab_group g on g.id=p.group_id where p.id=?",project.projectId());
        return new ContextView(project.projectKey(),new CurrentStage(current.stageKey(),current.attemptNo(),current.status()),actorId,approved,includeHistory,history,new NextArtifact(current.artifactKind(),"[]",current.gitRequired()),project.groupKey(),(String)names.get("group_name"),project.projectKey(),(String)names.get("requirement_name"),project.gitlabRootSnapshot(),current.id(),current.attemptNo());
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
    public TaskPromptView taskPrompt(String projectKey,String stageKey,Long actorId){var p=authorization.requireMemberOrThrow(projectKey,actorId);StageRow s=readStage(p.projectId(),normalizeStage(stageKey));requireStageOwner(p,s,actorId);String contextPrompt=s.order()>1?CollabStagePromptFactory.context(p.projectKey(),s.title()):null;String submitPrompt=CollabStagePromptFactory.submit(p.projectKey(),s.title(),s.id(),s.attemptNo(),s.gitRequired());return new TaskPromptView(p.projectKey(),s.stageKey(),s.attemptNo(),submitPrompt,contextPrompt);}

    private ArtifactView artifact(String projectKey,Long versionId,Long actorId){return jdbc.queryForObject("select v.id,v.artifact_id,v.version_no,v.attempt_no,v.sha256,v.size_bytes,v.git_evidence_json,a.kind,a.title,s.stage_key,pv.url feishu_url from collab_artifact_version v join collab_artifact a on a.id=v.artifact_id join collab_stage s on s.id=v.stage_id left join collab_feishu_publication pv on pv.artifact_version_id=v.id where v.id=?",(rs,n)->artifactView(rs,rs.getString("stage_key"),projectKey,false),versionId);}
    private SubmissionView submission(String projectKey,Long id,Long actor){var p=authorization.requireMemberOrThrow(projectKey,actor);return jdbc.queryForObject("select sub.id,sub.stage_id,s.stage_key,sub.attempt_no,sub.artifact_version_id,sub.status,s.status stage_status,coalesce((select ap.state from collab_approval ap where ap.submission_id=sub.id order by ap.id limit 1),'') approval_state,sub.submitted_at,sub.decided_at from collab_submission sub join collab_stage s on s.id=sub.stage_id where sub.id=? and s.project_id=?",(rs,n)->new SubmissionView(rs.getLong("id"),rs.getLong("stage_id"),rs.getString("stage_key"),rs.getInt("attempt_no"),rs.getLong("artifact_version_id"),rs.getString("status"),rs.getString("stage_status"),rs.getString("approval_state"),"PUBLISHING".equals(rs.getString("status"))?"WAIT_FEISHU":"PENDING_APPROVAL".equals(rs.getString("status"))?"WAIT_REVIEW":"NEXT_STAGE",instant(rs,"submitted_at"),instant(rs,"decided_at"),reviewerRoleProgress(rs.getLong("id"))),id,p.projectId());}
    private StageRow lockedStage(Long projectId,String key){return jdbc.query("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no,started_at,completed_at,actors_frozen_at from collab_stage where project_id=? and stage_key=? for update",rs->rs.next()?stage(rs):null,projectId,key);}
    private StageRow readStage(Long projectId,String key){StageRow stage=jdbc.query("select id,stage_key,stage_order,title,artifact_kind,git_required,owner_user_id,reviewer_user_id,status,attempt_no,version_no,started_at,completed_at,actors_frozen_at from collab_stage where project_id=? and stage_key=?",rs->rs.next()?stage(rs):null,projectId,key);if(stage==null)throw error("COLLAB_STAGE_NOT_FOUND","Stage not found",HttpStatus.NOT_FOUND);return stage;}
    private StageRow stage(ResultSet rs)throws SQLException{return new StageRow(rs.getLong("id"),rs.getString("stage_key"),rs.getInt("stage_order"),rs.getString("title"),rs.getString("artifact_kind"),rs.getBoolean("git_required"),rs.getLong("owner_user_id"),(Long)rs.getObject("reviewer_user_id"),rs.getString("status"),rs.getInt("attempt_no"),rs.getInt("version_no"),instant(rs,"started_at"),instant(rs,"completed_at"),instant(rs,"actors_frozen_at"));}
    private void completeStage(Long projectId,StageRow stage,Long actorId,Long submissionId,Instant now){
        int currentGroup=stageGroupIndex(stage.order());
        List<Integer> unfinished=jdbc.query("select stage_order from collab_stage where project_id=? and stage_order in ("+placeholders(STAGE_GROUPS.get(currentGroup).size())+") and status not in ('COMPLETED','SKIPPED') for update",
                (rs,n)->rs.getInt(1),concat(projectId,STAGE_GROUPS.get(currentGroup)));
        if(!unfinished.isEmpty())return;
        for(int groupIndex=currentGroup+1;groupIndex<STAGE_GROUPS.size();groupIndex++){
            List<Integer> orders=STAGE_GROUPS.get(groupIndex);
            List<StageActivation> nextStages=jdbc.query("select id,stage_order,status,stage_key from collab_stage where project_id=? and stage_order in ("+placeholders(orders.size())+") and status not in ('COMPLETED','SKIPPED') order by stage_order",
                    (rs,n)->new StageActivation(rs.getLong("id"),rs.getInt("stage_order"),rs.getString("status"),rs.getString("stage_key")),concat(projectId,orders));
            if(nextStages.isEmpty())continue;
            int nextOrder=nextStages.get(0).order();
            for(StageActivation next:nextStages){
                if(!stageHasRequiredActiveActors(projectId,next.id(),next.order())){
                    int blocked=jdbc.update("update collab_stage set status='READY_BLOCKED',actors_frozen_at=null,version_no=version_no+1,time_updated=? where id=? and status in ('WAITING','READY','NOT_STARTED','READY_BLOCKED')",now,next.id());
                    if(blocked==1){StageRow blockedStage=readStage(projectId,next.stageKey());addEvent(projectId,blockedStage,"ASSIGNMENT_BLOCKED",actorId,next.status(),"READY_BLOCKED",submissionId,Map.of("reason","NEXT_STAGE_ACTORS_REQUIRED"));}
                    continue;
                }
                int changed=jdbc.update("update collab_stage set actors_frozen_at=coalesce(actors_frozen_at,?),status='IN_PROGRESS',started_at=coalesce(started_at,?),version_no=version_no+1,time_updated=? where id=? and status in ('WAITING','READY','NOT_STARTED','READY_BLOCKED')",now,now,now,next.id());
                if(changed==1){freezeStageActorRows(next.id(), now);if("READY_BLOCKED".equals(next.status()))feishuOutbox.enqueueStageActivation(projectId,next.id(),jdbc.queryForObject("select version_no from collab_stage where id=?",Integer.class,next.id()));else feishuOutbox.enqueueStageHandoff(projectId,next.id(),submissionId);}
            }
            jdbc.update("update collab_project set current_stage_order=?,version_no=version_no+1,time_updated=? where id=?",nextOrder,now,projectId);
            return;
        }
        int completed=jdbc.update("update collab_project set status='COMPLETED',version_no=version_no+1,time_updated=? where id=? and status<>'COMPLETED'",now,projectId);
        if(completed==1)feishuOutbox.enqueueProjectCompleted(projectId);
    }
    private int stageGroupStart(int order){return order==4?3:order==6?5:order;}
    private int stageGroupEnd(int start){return start==3||start==5?start+1:start;}
    private int stageGroupIndex(int order){for(int i=0;i<STAGE_GROUPS.size();i++)if(STAGE_GROUPS.get(i).contains(order))return i;throw new IllegalArgumentException("Unsupported stage order: "+order);}
    private String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private Object[] concat(Object first,List<Integer> rest){Object[] args=new Object[rest.size()+1];args[0]=first;for(int i=0;i<rest.size();i++)args[i+1]=rest.get(i);return args;}
    private void lockProjectFlow(Long projectId){jdbc.queryForObject("select id from collab_project where id=? for update",Long.class,projectId);}
    private void addEvent(Long projectId,StageRow stage,String event,Long actor,String from,String to,Long reference,Object payload){jdbc.update("insert into collab_stage_event(time_created,time_updated,project_id,stage_id,attempt_no,event_type,actor_user_id,from_status,to_status,reference_id,payload_json,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",Instant.now(),Instant.now(),projectId,stage.id(),stage.attemptNo(),event,actor,from,to,reference,json(payload),Instant.now());}
    private List<Long> additionalReviewerIds(Long stageId){return jdbc.query("select reviewer_user_id from collab_stage_additional_reviewer where stage_id=? order by id",(rs,n)->rs.getLong(1),stageId);}
    private String stageRoleName(Long stageId,String duty){
        if("REVIEWER".equals(duty)){
            List<String> names=jdbc.query("select rr.role_name_snapshot from collab_requirement_stage_reviewer_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=? order by rr.role_name_snapshot",(rs,n)->rs.getString(1),stageId);
            if(!names.isEmpty()){
                String legacy=jdbc.query("select rr.role_name_snapshot from collab_requirement_stage_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=? and sr.duty='REVIEWER'",rs->rs.next()?rs.getString(1):null,stageId);
                if(legacy!=null){List<String> ordered=new ArrayList<>();ordered.add(legacy);names.stream().filter(name->!name.equals(legacy)).forEach(ordered::add);names=ordered;}
                return String.join("、",names);
            }
        }
        return jdbc.query("select rr.role_name_snapshot from collab_requirement_stage_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id where sr.stage_id=? and sr.duty=?",rs->rs.next()?rs.getString(1):null,stageId,duty);
    }
    private List<Long> reviewerIds(CollabProjectAuthorizationService.ProjectRef project,StageRow stage){if(project.groupId()!=null)return stageActorIds(project,stage,"REVIEWER",true);List<Long> ids=new ArrayList<>();if(stage.reviewerUserId()!=null)ids.add(stage.reviewerUserId());ids.addAll(additionalReviewerIds(stage.id()));return ids.stream().distinct().toList();}
    private List<Long> stageActorIds(CollabProjectAuthorizationService.ProjectRef project,StageRow stage,String duty,boolean frozenOnly){if(project.groupId()==null){if("OWNER".equals(duty))return stage.ownerUserId()==null?List.of():List.of(stage.ownerUserId());List<Long> ids=new ArrayList<>();if(stage.reviewerUserId()!=null)ids.add(stage.reviewerUserId());ids.addAll(additionalReviewerIds(stage.id()));return ids.stream().distinct().toList();}if(frozenOnly&&stage.actorsFrozenAt()==null)return List.of();String sql="select a.user_id from collab_requirement_stage_actor a join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty=?"+(frozenOnly?" and a.frozen_at is not null":"")+" order by a.user_id";return jdbc.query(sql,(rs,n)->rs.getLong(1),stage.id(),duty);}
    private void requireStageOwner(CollabProjectAuthorizationService.ProjectRef project,StageRow stage,Long actor){if(project.groupId()!=null&&stage.actorsFrozenAt()==null)throw error("REQUIREMENT_STAGE_ACTORS_NOT_FROZEN","Stage actors must be frozen before work starts",HttpStatus.CONFLICT);if(!stageActorIds(project,stage,"OWNER",true).contains(actor))throw error("COLLAB_STAGE_OWNER_REQUIRED","Only a frozen current stage owner may perform this operation",HttpStatus.FORBIDDEN);authorization.requireActiveMember(project.projectId(),actor);}
    private void freezeStageActorRows(Long stageId,Instant now){jdbc.update("update collab_requirement_stage_actor set frozen_at=coalesce(frozen_at,?) where stage_id=?",now,stageId);}
    private boolean stageHasRequiredActiveActors(Long projectId,Long stageId,int order){
        String stageKey=jdbc.queryForObject("select stage_key from collab_stage where id=?",String.class,stageId);
        Long groupId=jdbc.queryForObject("select group_id from collab_project where id=?",Long.class,projectId);
        if(groupId==null){Integer owners=jdbc.queryForObject("select count(*) from collab_stage s join collab_project_member m on m.project_id=s.project_id and m.user_id=s.owner_user_id and m.status='ACTIVE' join iam_user u on u.id=m.user_id and u.status='ACTIVE' where s.id=?",Integer.class,stageId);if(owners==null||owners==0)return false;if(requiresReviewer(jdbc.queryForObject("select approval_level from collab_project where id=?",String.class,projectId),stageKey)){Integer reviewers=jdbc.queryForObject("select (select count(*) from collab_stage s join collab_project_member m on m.project_id=s.project_id and m.user_id=s.reviewer_user_id and m.status='ACTIVE' join iam_user u on u.id=m.user_id and u.status='ACTIVE' where s.id=?)+(select count(*) from collab_stage_additional_reviewer ar join collab_project_member m on m.project_id=? and m.user_id=ar.reviewer_user_id and m.status='ACTIVE' join iam_user u on u.id=m.user_id and u.status='ACTIVE' where ar.stage_id=?)",Integer.class,stageId,projectId,stageId);return reviewers!=null&&reviewers>0;}return true;}
        Integer owners=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty='OWNER'",Integer.class,groupId,stageId);if(owners==null||owners==0)return false;
        String level=jdbc.queryForObject("select approval_level from collab_project where id=?",String.class,projectId);if("L3".equals(level))return true;
        Integer assignedReviewers=jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=?",Integer.class,groupId,stageId);
        if("L2".equals(level)&&(assignedReviewers==null||assignedReviewers==0))return true;
        Integer roleCount=jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role where stage_id=?",Integer.class,stageId);
        if(roleCount!=null&&roleCount>0){Integer missing=jdbc.queryForObject("select count(*) from collab_requirement_stage_reviewer_role rr where rr.stage_id=? and not exists(select 1 from collab_requirement_stage_reviewer_role_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=rr.stage_id and a.requirement_role_id=rr.requirement_role_id)",Integer.class,stageId,groupId);return missing!=null&&missing==0;}
        Integer reviewers=jdbc.queryForObject("select count(*) from collab_requirement_stage_actor a join collab_group_member gm on gm.group_id=? and gm.user_id=a.user_id and gm.status='ACTIVE' join iam_user u on u.id=a.user_id and u.status='ACTIVE' where a.stage_id=? and a.duty='REVIEWER'",Integer.class,groupId,stageId);return reviewers!=null&&reviewers>0;
    }
    private List<Long> reviewerStateIds(Long submissionId,String state){if(submissionId==null)return List.of();return jdbc.query("select reviewer_user_id from collab_approval where submission_id=? and state=? order by reviewer_user_id",(rs,n)->rs.getLong(1),submissionId,state);}
    private List<Long> pendingReviewerStateIds(Long submissionId){if(submissionId==null)return List.of();return jdbc.query("select ap.reviewer_user_id from collab_approval ap where ap.submission_id=? and ap.state='PENDING' and (not exists(select 1 from collab_submission_reviewer_role r where r.submission_id=ap.submission_id) or exists(select 1 from collab_submission_reviewer_assignment a where a.approval_id=ap.id and exists(select 1 from collab_submission_reviewer_role r where r.submission_id=a.submission_id and r.requirement_role_id=a.requirement_role_id and not exists(select 1 from collab_submission_reviewer_assignment aa join collab_approval approved on approved.id=aa.approval_id where aa.submission_id=r.submission_id and aa.requirement_role_id=r.requirement_role_id and approved.state='APPROVED')))) order by ap.reviewer_user_id",(rs,n)->rs.getLong(1),submissionId);}
    private List<ReviewerRoleProgress> reviewerRoleProgress(Long submissionId){if(submissionId==null)return List.of();return jdbc.query("select r.role_key_snapshot,r.role_name_snapshot,sub.status submission_status,case when exists(select 1 from collab_submission_reviewer_assignment a join collab_approval ap on ap.id=a.approval_id where a.submission_id=r.submission_id and a.requirement_role_id=r.requirement_role_id and ap.state='APPROVED') then 1 else 0 end approved_count from collab_submission_reviewer_role r join collab_submission sub on sub.id=r.submission_id where r.submission_id=? order by r.role_name_snapshot,r.role_key_snapshot",(rs,n)->{int count=rs.getInt("approved_count");String status="REJECTED".equals(rs.getString("submission_status"))?"REJECTED":count==1?"SATISFIED":"PENDING";return new ReviewerRoleProgress(rs.getString("role_key_snapshot"),rs.getString("role_name_snapshot"),count,1,status);},submissionId);}
    private List<ReviewerRoleRoster> reviewerRoleRosters(Long stageId){return jdbc.query("select rr.id,rr.source_group_role_key,rr.role_name_snapshot,a.user_id,u.status user_status from collab_requirement_stage_reviewer_role sr join collab_requirement_role rr on rr.id=sr.requirement_role_id left join collab_requirement_stage_reviewer_role_actor a on a.stage_id=sr.stage_id and a.requirement_role_id=sr.requirement_role_id left join iam_user u on u.id=a.user_id where sr.stage_id=? order by rr.role_name_snapshot,rr.source_group_role_key,a.user_id",rs-> {Map<Long,ReviewerRoleRosterBuilder> map=new LinkedHashMap<>();while(rs.next()){long id=rs.getLong("id");ReviewerRoleRosterBuilder b=map.get(id);if(b==null){b=new ReviewerRoleRosterBuilder(id,rs.getString("source_group_role_key"),rs.getString("role_name_snapshot"));map.put(id,b);}Object userValue=rs.getObject("user_id");if(userValue!=null&&"ACTIVE".equals(rs.getString("user_status")))b.users.add(((Number)userValue).longValue());}return map.values().stream().map(b->new ReviewerRoleRoster(b.id,b.key,b.name,List.copyOf(b.users))).toList();},stageId);}
    private void validateArtifact(CollabProjectAuthorizationService.ProjectRef project,StageRow stage,ArtifactUpload r){if(r.title().isBlank()||r.markdownContent().isBlank())throw error("COLLAB_ARTIFACT_EMPTY","Title and Markdown content are required",HttpStatus.BAD_REQUEST);byte[] bytes=r.markdownContent().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_MARKDOWN_BYTES)throw error("COLLAB_ARTIFACT_TOO_LARGE","Markdown content must not exceed 1 MB",HttpStatus.PAYLOAD_TOO_LARGE);if(!r.markdownContent().equals(new String(bytes,StandardCharsets.UTF_8)))throw error("COLLAB_ARTIFACT_ENCODING_INVALID","Markdown must be valid UTF-8",HttpStatus.BAD_REQUEST);String actual=sha256(r.markdownContent());if(!actual.equalsIgnoreCase(r.sha256()))throw error("COLLAB_ARTIFACT_HASH_MISMATCH","SHA-256 does not match the Markdown content",HttpStatus.UNPROCESSABLE_ENTITY);if(stage.gitRequired()){if(r.gitEvidence()==null)throw error("COLLAB_GIT_EVIDENCE_REQUIRED","Repository and commit/PR URLs are required",HttpStatus.UNPROCESSABLE_ENTITY);if(project.gitlabRootSnapshot()!=null&&!project.gitlabRootSnapshot().isBlank())gitlabRootValidator.validateEvidence(project.gitlabRootSnapshot(),r.gitEvidence().repositoryUrl(),r.gitEvidence().commitOrPullRequestUrl());else{validateGitUrl(r.gitEvidence().repositoryUrl());validateGitUrl(r.gitEvidence().commitOrPullRequestUrl());}}}
    private void validateGitUrl(String value){try{java.net.URI uri=java.net.URI.create(value);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);if(!"https".equalsIgnoreCase(uri.getScheme())||!(host.equals("github.com")||host.endsWith(".github.com")||host.equals("gitlab.com")||host.endsWith(".gitlab.com")||host.equals("gitlab.it.taikang.com"))||uri.getUserInfo()!=null)throw new IllegalArgumentException();}catch(RuntimeException ex){throw error("COLLAB_GIT_URL_INVALID","Git evidence must use an HTTPS GitHub or GitLab URL",HttpStatus.UNPROCESSABLE_ENTITY);}}
    private void requireWikiRootConfigured(Long projectId){Integer configured=jdbc.queryForObject("select count(*) from collab_feishu_binding where project_id=? and wiki_space_id is not null and wiki_root_node_token is not null and publish_actor_user_id is not null",Integer.class,projectId);if(configured==null||configured==0)throw error("COLLAB_WIKI_ROOT_REQUIRED","Configure the project Feishu Wiki root before submitting an artifact",HttpStatus.CONFLICT);}
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

    private record StageRow(Long id,String stageKey,int order,String title,String artifactKind,boolean gitRequired,Long ownerUserId,Long reviewerUserId,String status,int attemptNo,int versionNo,Instant startedAt,Instant completedAt,Instant actorsFrozenAt){}
    private record StageActivation(Long id,int order,String status,String stageKey){}
    public record WorkflowView(String projectKey,String approvalLevel,String projectStatus,int currentStageOrder,List<StageWorkflowView> stages){}
    public record StageWorkflowView(Long stageId,String stageKey,int order,String title,String artifactKind,boolean gitRequired,Long ownerUserId,Long reviewerUserId,List<Long> additionalReviewerUserIds,String status,int attemptNo,int versionNo,Long submissionId,String submissionStatus,Long artifactVersionId,Instant submittedAt,String approvalState,Long frozenReviewerUserId,Integer approvalVersionNo,String ownerRoleName,String reviewerRoleName,List<Long> pendingReviewerUserIds,List<Long> approvedReviewerUserIds,List<ReviewerRoleProgress> reviewerRoleProgress){}
    public record ArtifactView(Long artifactVersionId,Long artifactId,int versionNo,int attemptNo,String stageKey,String kind,String title,String sha256,long sizeBytes,String feishuUrl,String contentUrl,String repositoryUrl,String commitOrPullRequestUrl,boolean pendingApproval){}
    public record ArtifactContentView(Long artifactVersionId,String stageKey,String title,String sha256,long sizeBytes,String markdownContent){}
    public record SubmissionView(Long submissionId,Long stageId,String stageKey,int attemptNo,Long artifactVersionId,String submissionStatus,String stageStatus,String approvalState,String nextAction,Instant submittedAt,Instant decidedAt,List<ReviewerRoleProgress> reviewerRoleProgress){}
    public record ReviewerRoleProgress(String roleKey,String roleName,int approvedCount,int requiredCount,String status){}
    private record ReviewerRoleRoster(Long roleId,String roleKey,String roleName,List<Long> userIds){}
    private static final class ReviewerRoleRosterBuilder { final Long id; final String key,name; final List<Long> users=new ArrayList<>(); ReviewerRoleRosterBuilder(Long id,String key,String name){this.id=id;this.key=key;this.name=name;} }
    public record CurrentStage(String key,int attemptNo,String status){}
    public record NextArtifact(String kind,String requiredFields,boolean gitEvidenceRequired){}
    public record ContextView(String projectKey,CurrentStage currentStage,Long actorUserId,List<ArtifactView> approvedArtifacts,boolean historyIncluded,List<ArtifactView> history,NextArtifact nextExpectedArtifact,String groupKey,String groupName,String requirementKey,String requirementName,String gitlabRootUrl,Long stageId,int attemptNo){}
    public record EventView(Long id,Long stageId,String stageKey,Integer attemptNo,String eventType,Long actorUserId,String actorName,String fromStatus,String toStatus,Long referenceId,Instant createdAt){}
    public record TaskPromptView(String projectKey,String stageKey,int attemptNo,String prompt,String contextPrompt){}
}
