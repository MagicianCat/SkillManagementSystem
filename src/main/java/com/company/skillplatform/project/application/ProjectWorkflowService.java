package com.company.skillplatform.project.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.project.infrastructure.entity.VirtualProjectEntity;
import com.company.skillplatform.project.infrastructure.repository.VirtualProjectMemberRepository;
import com.company.skillplatform.project.infrastructure.repository.VirtualProjectRepository;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow aggregate for the project phase board. SQL is kept local so the
 * state-machine invariants remain atomic without leaking into document CRUD. */
@Service
public class ProjectWorkflowService {
    public static final List<String> AVAILABLE = List.of("REQUIREMENT", "PRD", "ARCHITECTURE", "UI_DESIGN");
    public static final List<String> FUTURE = List.of("CODING", "SECURITY", "TESTING", "RELEASE");
    public static final Set<String> FUNCTIONAL_ROLES = Set.of(
            "REQUIREMENT_ANALYST", "REQUIREMENT_REVIEWER", "PRD_AUTHOR", "PRD_REVIEWER",
            "ARCHITECT", "ARCHITECTURE_REVIEWER", "UI_DESIGNER", "UI_REVIEWER");
    private static final Map<String, String> EXECUTOR = Map.of(
            "REQUIREMENT", "REQUIREMENT_ANALYST", "PRD", "PRD_AUTHOR",
            "ARCHITECTURE", "ARCHITECT", "UI_DESIGN", "UI_DESIGNER");
    private static final Map<String, String> REVIEWER = Map.of(
            "REQUIREMENT", "REQUIREMENT_REVIEWER", "PRD", "PRD_REVIEWER",
            "ARCHITECTURE", "ARCHITECTURE_REVIEWER", "UI_DESIGN", "UI_REVIEWER");
    private static final Map<String, String> PROFILE = Map.of(
            "REQUIREMENT", "requirement-analysis/v1", "PRD", "prd-authoring/v1",
            "ARCHITECTURE", "architecture-design/v1", "UI_DESIGN", "ui-design/v1");

    private final JdbcTemplate jdbc;
    private final VirtualProjectRepository projects;
    private final VirtualProjectMemberRepository members;
    private final ObjectStoragePort storage;

    public ProjectWorkflowService(JdbcTemplate jdbc, VirtualProjectRepository projects,
                                  VirtualProjectMemberRepository members, ObjectStoragePort storage) {
        this.jdbc = jdbc; this.projects = projects; this.members = members; this.storage = storage;
    }

    @Transactional
    public void initialize(Long projectId, Collection<String> enabledStages) {
        Set<String> enabled = enabledStages == null || enabledStages.isEmpty() ? Set.copyOf(AVAILABLE) : new HashSet<>(enabledStages);
        List<String> stages = new ArrayList<>(); stages.addAll(AVAILABLE); stages.addAll(FUTURE);
        for (int i = 0; i < stages.size(); i++) {
            String key = stages.get(i); boolean available = AVAILABLE.contains(key);
            jdbc.update("insert into project_stage(time_created,time_updated,project_id,stage_key,stage_order,availability,enabled,status,cycle_no,version_no) values(now(3),now(3),?,?,?,?,?,'NOT_STARTED',0,0)",
                    projectId, key, i + 1, available ? "AVAILABLE" : "FUTURE", available && enabled.contains(key));
        }
        dependency(projectId, "PRD", "REQUIREMENT");
        dependency(projectId, "ARCHITECTURE", "PRD");
        dependency(projectId, "UI_DESIGN", "PRD");
    }

    @Transactional(readOnly = true)
    public WorkflowView workflow(String projectKey, Long actorId) {
        VirtualProjectEntity project = access(projectKey, actorId);
        List<Map<String,Object>> stageRows = jdbc.queryForList("select id,stage_key,stage_order,availability,enabled,status,cycle_no,started_at,completed_at from project_stage where project_id=? order by stage_order", project.getId());
        List<StageView> stages = stageRows.stream().map(r -> stageView(project.getId(), r)).toList();
        int denominator = (int) stages.stream().filter(s -> s.enabled() && "AVAILABLE".equals(s.availability())).count() * 100;
        int numerator = stages.stream().filter(s -> s.enabled() && "AVAILABLE".equals(s.availability())).mapToInt(StageView::progress).sum();
        return new WorkflowView(projectKey, denominator == 0 ? 0 : numerator * 100 / denominator, stages, functionalRoles(project.getId()));
    }

    @Transactional
    public List<MemberRoleView> assignRoles(String projectKey, Long userId, Set<String> roles, Long actorId) {
        VirtualProjectEntity project = managed(projectKey, actorId);
        if (roles == null || !FUNCTIONAL_ROLES.containsAll(roles)) throw error("PROJECT_FUNCTIONAL_ROLE_INVALID", "Unsupported project functional role", HttpStatus.BAD_REQUEST);
        // The creator is the project owner even while membership synchronization is
        // catching up; this also permits an owner to assign roles to themself.
        if (!members.existsByProjectIdAndUserIdAndStatus(project.getId(), userId, "ACTIVE")
                && !project.getCreatedBy().getId().equals(userId))
            throw error("PROJECT_MEMBER_REQUIRED", "Add the user to the project first", HttpStatus.BAD_REQUEST);
        jdbc.update("delete from project_member_functional_role where project_id=? and user_id=?", project.getId(), userId);
        for (String role : roles) jdbc.update("insert into project_member_functional_role(time_created,time_updated,project_id,user_id,role_key,assigned_by) values(now(3),now(3),?,?,?,?)", project.getId(), userId, role, actorId);
        return functionalRoles(project.getId()).stream().filter(v -> v.userId().equals(userId)).toList();
    }

    @Transactional
    public StageView configureSkills(String projectKey, String stageKey, List<String> skillKeys, Long actorId) {
        VirtualProjectEntity project = managed(projectKey, actorId); Map<String,Object> stage = stage(project.getId(), stageKey);
        if (!"NOT_STARTED".equals(stage.get("status"))) throw error("PROJECT_STAGE_ALREADY_STARTED", "Skills can only be changed before a stage starts", HttpStatus.CONFLICT);
        jdbc.update("delete from project_stage_skill where stage_id=?", number(stage, "id"));
        for (String key : new LinkedHashSet<>(skillKeys == null ? List.of() : skillKeys)) {
            List<Long> ids = jdbc.query("select s.id from skill s where s.skill_key=? and s.status='ACTIVE' and s.latest_published_version_id is not null and (s.scope_type='PLATFORM' or exists(select 1 from org_team_member m where m.team_id=s.team_id and m.user_id=? and m.status='ACTIVE'))", (rs,n)->rs.getLong(1), key, actorId);
            if (ids.isEmpty()) throw error("PROJECT_STAGE_SKILL_NOT_FOUND", "Published skill not found: " + key, HttpStatus.BAD_REQUEST);
            jdbc.update("insert into project_stage_skill(time_created,time_updated,stage_id,skill_id) values(now(3),now(3),?,?)", number(stage,"id"), ids.get(0));
        }
        return stageView(project.getId(), stage(project.getId(), stageKey));
    }

    @Transactional
    public StageView start(String projectKey, String stageKey, Long actorId) {
        VirtualProjectEntity project = access(projectKey, actorId); Map<String,Object> stage = stage(project.getId(), stageKey);
        if (!Boolean.TRUE.equals(stage.get("enabled")) || !"AVAILABLE".equals(stage.get("availability"))) throw error("PROJECT_STAGE_UNAVAILABLE", "Stage is not available", HttpStatus.CONFLICT);
        if (!manager(project.getId(), actorId) && !hasRole(project.getId(), actorId, EXECUTOR.get(stageKey))) throw error("PROJECT_STAGE_START_FORBIDDEN", "Project manager or stage executor required", HttpStatus.FORBIDDEN);
        if (!"NOT_STARTED".equals(stage.get("status"))) throw error("PROJECT_STAGE_ALREADY_STARTED", "Stage has already started", HttpStatus.CONFLICT);
        Integer blocked = jdbc.queryForObject("select count(*) from project_stage_dependency d join project_stage p on p.id=d.depends_on_stage_id where d.stage_id=? and p.enabled=true and p.status<>'COMPLETED'", Integer.class, number(stage,"id"));
        if (blocked != null && blocked > 0) throw error("PROJECT_STAGE_DEPENDENCY_INCOMPLETE", "Complete prerequisite stages first", HttpStatus.CONFLICT);
        lockSkills(number(stage,"id"));
        jdbc.update("update project_stage set status='IN_PROGRESS',cycle_no=1,started_by=?,started_at=now(3),time_updated=now(3),version_no=version_no+1 where id=?", actorId, number(stage,"id"));
        return stageView(project.getId(), stage(project.getId(), stageKey));
    }

    @Transactional
    public SubmissionView submit(String projectKey, String stageKey, Long documentId, Long revisionId, Long actorId) {
        VirtualProjectEntity project = access(projectKey, actorId); Map<String,Object> stage = stage(project.getId(), stageKey);
        if (!hasRole(project.getId(), actorId, EXECUTOR.get(stageKey))) throw error("PROJECT_STAGE_SUBMIT_FORBIDDEN", "Stage executor role required", HttpStatus.FORBIDDEN);
        String status = String.valueOf(stage.get("status"));
        if (!Set.of("IN_PROGRESS", "REWORK").contains(status)) throw error("PROJECT_STAGE_NOT_EDITABLE", "Stage cannot be submitted", HttpStatus.CONFLICT);
        Integer valid = jdbc.queryForObject("select count(*) from project_stage_artifact a join project_document d on d.id=a.document_id join project_document_revision r on r.document_id=d.id where a.stage_id=? and d.id=? and r.id=? and d.project_id=? and d.document_type=?", Integer.class, number(stage,"id"), documentId, revisionId, project.getId(), stageKey);
        if (valid == null || valid == 0) throw error("PROJECT_STAGE_ARTIFACT_INVALID", "Document revision does not belong to this stage", HttpStatus.BAD_REQUEST);
        int cycle = ((Number)stage.get("cycle_no")).intValue() + ("REWORK".equals(status) ? 1 : 0);
        List<Long> reviewers = roleUsers(project.getId(), REVIEWER.get(stageKey));
        if (reviewers.isEmpty()) throw error("PROJECT_STAGE_REVIEWER_REQUIRED", "Assign at least one stage reviewer", HttpStatus.CONFLICT);
        jdbc.update("insert into project_stage_submission(time_created,time_updated,stage_id,cycle_no,document_id,revision_id,submitted_by,status) values(now(3),now(3),?,?,?,?,?,'PENDING')", number(stage,"id"), cycle, documentId, revisionId, actorId);
        Long submissionId = jdbc.queryForObject("select id from project_stage_submission where stage_id=? and cycle_no=?", Long.class, number(stage,"id"), cycle);
        for (Long reviewer : reviewers) jdbc.update("insert into project_stage_reviewer(submission_id,reviewer_user_id) values(?,?)", submissionId, reviewer);
        jdbc.update("update project_stage set status='IN_REVIEW',cycle_no=?,time_updated=now(3),version_no=version_no+1 where id=?", cycle, number(stage,"id"));
        return submission(submissionId);
    }

    @Transactional
    public SubmissionView review(String projectKey, String stageKey, Long submissionId, String decision, String comment, Long actorId) {
        VirtualProjectEntity project = access(projectKey, actorId); Map<String,Object> stage = stage(project.getId(), stageKey);
        Integer assigned = jdbc.queryForObject("select count(*) from project_stage_reviewer r join project_stage_submission s on s.id=r.submission_id where r.submission_id=? and r.reviewer_user_id=? and s.stage_id=? and s.status='PENDING'", Integer.class, submissionId, actorId, number(stage,"id"));
        if (assigned == null || assigned == 0) throw error("PROJECT_STAGE_REVIEW_FORBIDDEN", "Frozen reviewer membership required", HttpStatus.FORBIDDEN);
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) throw error("PROJECT_STAGE_REVIEW_INVALID", "Decision must be APPROVED or REJECTED", HttpStatus.BAD_REQUEST);
        jdbc.update("insert into project_stage_review(time_created,time_updated,submission_id,reviewer_user_id,decision,comment) values(now(3),now(3),?,?,?,?) on duplicate key update time_updated=now(3),decision=values(decision),comment=values(comment)", submissionId, actorId, decision, comment);
        if ("REJECTED".equals(decision)) {
            jdbc.update("update project_stage_submission set status='REJECTED',decided_at=now(3),time_updated=now(3) where id=?", submissionId);
            jdbc.update("update project_stage set status='REWORK',time_updated=now(3),version_no=version_no+1 where id=?", number(stage,"id"));
        } else {
            Integer remaining = jdbc.queryForObject("select count(*) from project_stage_reviewer r left join project_stage_review v on v.submission_id=r.submission_id and v.reviewer_user_id=r.reviewer_user_id and v.decision='APPROVED' where r.submission_id=? and v.id is null", Integer.class, submissionId);
            if (remaining != null && remaining == 0) {
                jdbc.update("update project_stage_submission set status='APPROVED',decided_at=now(3),time_updated=now(3) where id=?", submissionId);
                jdbc.update("update project_stage set status='COMPLETED',completed_at=now(3),time_updated=now(3),version_no=version_no+1 where id=?", number(stage,"id"));
            }
        }
        return submission(submissionId);
    }

    @Transactional(readOnly = true)
    public List<UserSearchView> searchUsers(String query, Long actorId) {
        String value = "%" + (query == null ? "" : query.trim()) + "%";
        return jdbc.query("select u.id,u.username,u.display_name,t.team_name from iam_user u left join org_team_member m on m.user_id=u.id and m.status='ACTIVE' left join org_team t on t.id=m.team_id where u.status='ACTIVE' and (u.username like ? or u.display_name like ?) order by u.display_name limit 30",
                (rs,n) -> new UserSearchView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)), value, value);
    }

    @Transactional(readOnly = true)
    public AgentStageContext agentContext(String projectKey, String stageKey, Long actorId) {
        VirtualProjectEntity project = access(projectKey, actorId); Map<String,Object> stage = stage(project.getId(), stageKey);
        if (!Set.of("IN_PROGRESS","REWORK","IN_REVIEW").contains(String.valueOf(stage.get("status")))) throw error("PROJECT_STAGE_NOT_STARTED", "Start the stage first", HttpStatus.CONFLICT);
        if (!hasRole(project.getId(), actorId, EXECUTOR.get(stageKey))) throw error("PROJECT_STAGE_AGENT_FORBIDDEN", "Stage executor role required", HttpStatus.FORBIDDEN);
        List<ManagedSkill> skills = jdbc.query("select s.skill_key,ps.locked_version_id,ps.locked_version,ps.locked_sha256,ps.locked_content from project_stage_skill ps join skill s on s.id=ps.skill_id where ps.stage_id=? order by s.skill_key",
                (rs,n)->new ManagedSkill(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5)), number(stage,"id"));
        return new AgentStageContext(number(stage,"id"), stageKey, PROFILE.get(stageKey), skills);
    }

    @Transactional(readOnly = true)
    public AgentStageContext agentContext(Long stageId, Long actorId) {
        Map<String,Object> row=jdbc.queryForMap("select p.project_key,s.stage_key from project_stage s join virtual_project p on p.id=s.project_id where s.id=?",stageId);
        return agentContext(String.valueOf(row.get("project_key")),String.valueOf(row.get("stage_key")),actorId);
    }

    @Transactional(readOnly = true)
    public void assertAgentWritable(Long stageId, Long actorId) {
        Map<String,Object> row=jdbc.queryForMap("select p.id project_id,s.stage_key,s.status from project_stage s join virtual_project p on p.id=s.project_id where s.id=?",stageId);
        if (!hasRole(number(row,"project_id"), actorId, EXECUTOR.get(String.valueOf(row.get("stage_key"))))) throw error("PROJECT_STAGE_AGENT_FORBIDDEN", "Stage executor role required", HttpStatus.FORBIDDEN);
        if (!Set.of("IN_PROGRESS","REWORK").contains(String.valueOf(row.get("status")))) throw error("PROJECT_STAGE_NOT_EDITABLE", "Stage conversation is read-only while under review", HttpStatus.CONFLICT);
    }

    @Transactional
    public void linkArtifact(Long stageId, Long documentId, Long jobId, Long actorId) {
        Integer valid=jdbc.queryForObject("select count(*) from project_stage s join project_document d on d.project_id=s.project_id and d.document_type=s.stage_key where s.id=? and d.id=?",Integer.class,stageId,documentId);
        if(valid==null||valid==0)throw error("PROJECT_STAGE_ARTIFACT_INVALID","Document does not belong to this stage",HttpStatus.BAD_REQUEST);
        jdbc.update("insert ignore into project_stage_artifact(time_created,time_updated,stage_id,document_id,source_job_id,added_by) values(now(3),now(3),?,?,?,?)",stageId,documentId,jobId,actorId);
    }

    @Transactional(readOnly = true)
    public void assertStageArtifact(Long stageId, Long documentId) {
        Integer n=jdbc.queryForObject("select count(*) from project_stage_artifact where stage_id=? and document_id=?",Integer.class,stageId,documentId);
        if(n==null||n==0)throw error("PROJECT_STAGE_ARTIFACT_INVALID","Document is not a stage artifact",HttpStatus.BAD_REQUEST);
    }

    private void lockSkills(long stageId) {
        List<Map<String,Object>> rows = jdbc.queryForList("select ps.id,s.skill_key,v.id version_id,v.version,v.source_sha256,v.source_object_key from project_stage_skill ps join skill s on s.id=ps.skill_id join skill_version v on v.id=s.latest_published_version_id where ps.stage_id=?", stageId);
        for (Map<String,Object> row : rows) jdbc.update("update project_stage_skill set locked_version_id=?,locked_version=?,locked_sha256=?,locked_content=?,time_updated=now(3) where id=?",
                number(row,"version_id"), row.get("version"), row.get("source_sha256"), readSkill(String.valueOf(row.get("source_object_key"))), number(row,"id"));
    }

    private String readSkill(String objectKey) {
        try (InputStream raw = storage.get(objectKey); ZipInputStream zip = new ZipInputStream(raw)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName().replace('\\','/');
                if (!entry.isDirectory() && (name.equals("SKILL.md") || name.endsWith("/SKILL.md"))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int total=0;
                    for(int read;(read=zip.read(buffer))!=-1;){total+=read;if(total>1_000_000)throw error("PROJECT_STAGE_SKILL_TOO_LARGE", "SKILL.md exceeds 1 MB", HttpStatus.BAD_REQUEST);out.write(buffer,0,read);}
                    return out.toString(StandardCharsets.UTF_8);
                }
            }
            throw error("PROJECT_STAGE_SKILL_CONTENT_MISSING", "Published skill has no SKILL.md", HttpStatus.BAD_REQUEST);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw error("PROJECT_STAGE_SKILL_READ_FAILED", "Unable to read published skill", HttpStatus.BAD_GATEWAY); }
    }

    private StageView stageView(Long projectId, Map<String,Object> r) {
        long stageId = number(r,"id"); String status = String.valueOf(r.get("status"));
        int progress = switch (status) { case "IN_PROGRESS", "REWORK" -> 20; case "IN_REVIEW" -> 70; case "COMPLETED" -> 100; default -> 0; };
        if (progress == 20) {
            Integer artifacts=jdbc.queryForObject("select count(*) from project_stage_artifact where stage_id=?",Integer.class,stageId);
            if(artifacts!=null&&artifacts>0)progress=40;
        }
        List<SkillView> skills = jdbc.query("select s.skill_key,s.display_name,ps.locked_version from project_stage_skill ps join skill s on s.id=ps.skill_id where ps.stage_id=? order by s.display_name", (rs,n)->new SkillView(rs.getString(1),rs.getString(2),rs.getString(3)), stageId);
        List<String> dependencies = jdbc.query("select p.stage_key from project_stage_dependency d join project_stage p on p.id=d.depends_on_stage_id where d.stage_id=?", (rs,n)->rs.getString(1), stageId);
        List<SubmissionView> submissions = jdbc.query("select id from project_stage_submission where stage_id=? order by cycle_no desc", (rs,n)->submission(rs.getLong(1)), stageId);
        List<ArtifactView> artifacts = jdbc.query("select d.id,d.title,d.document_type,r.id revision_id,r.revision_no,a.time_created from project_stage_artifact a join project_document d on d.id=a.document_id left join project_document_revision r on r.id=d.current_draft_revision_id where a.stage_id=? order by a.time_created desc",(rs,n)->new ArtifactView(rs.getLong(1),rs.getString(2),rs.getString(3),(Long)rs.getObject(4),rs.getObject(5)==null?null:rs.getInt(5),rs.getTimestamp(6).toInstant()),stageId);
        return new StageView(stageId,String.valueOf(r.get("stage_key")),((Number)r.get("stage_order")).intValue(),String.valueOf(r.get("availability")),Boolean.TRUE.equals(r.get("enabled")),status,((Number)r.get("cycle_no")).intValue(),progress,EXECUTOR.get(String.valueOf(r.get("stage_key"))),REVIEWER.get(String.valueOf(r.get("stage_key"))),dependencies,skills,submissions,artifacts);
    }

    private SubmissionView submission(Long id) {
        Map<String,Object> r = jdbc.queryForMap("select id,cycle_no,document_id,revision_id,submitted_by,status,decided_at from project_stage_submission where id=?", id);
        List<ReviewView> reviews = jdbc.query("select f.reviewer_user_id,u.display_name,v.decision,v.comment from project_stage_reviewer f join iam_user u on u.id=f.reviewer_user_id left join project_stage_review v on v.submission_id=f.submission_id and v.reviewer_user_id=f.reviewer_user_id where f.submission_id=? order by u.display_name", (rs,n)->new ReviewView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)), id);
        return new SubmissionView(id,((Number)r.get("cycle_no")).intValue(),number(r,"document_id"),number(r,"revision_id"),number(r,"submitted_by"),String.valueOf(r.get("status")),reviews);
    }

    private List<MemberRoleView> functionalRoles(Long projectId) {
        return jdbc.query("select r.user_id,u.display_name,r.role_key from project_member_functional_role r join iam_user u on u.id=r.user_id where r.project_id=? order by u.display_name,r.role_key", (rs,n)->new MemberRoleView(rs.getLong(1),rs.getString(2),rs.getString(3)), projectId);
    }
    private List<Long> roleUsers(Long projectId, String role) { return jdbc.query("select user_id from project_member_functional_role where project_id=? and role_key=?", (rs,n)->rs.getLong(1), projectId, role); }
    private boolean hasRole(Long projectId, Long userId, String role) { if (role == null) return false; Integer n=jdbc.queryForObject("select count(*) from project_member_functional_role where project_id=? and user_id=? and role_key=?",Integer.class,projectId,userId,role);return n!=null&&n>0; }
    private void dependency(Long projectId,String stage,String parent){jdbc.update("insert into project_stage_dependency(stage_id,depends_on_stage_id) select s.id,p.id from project_stage s join project_stage p on p.project_id=s.project_id where s.project_id=? and s.stage_key=? and p.stage_key=?",projectId,stage,parent);}
    private Map<String,Object> stage(Long projectId,String key){if(!AVAILABLE.contains(key)&&!FUTURE.contains(key))throw error("PROJECT_STAGE_NOT_FOUND","Project stage not found",HttpStatus.NOT_FOUND);List<Map<String,Object>> rows=jdbc.queryForList("select id,stage_key,stage_order,availability,enabled,status,cycle_no,started_at,completed_at from project_stage where project_id=? and stage_key=?",projectId,key);if(rows.isEmpty())throw error("PROJECT_STAGE_NOT_FOUND","Project stage not found",HttpStatus.NOT_FOUND);return rows.get(0);}
    private VirtualProjectEntity access(String key,Long actor){VirtualProjectEntity p=projects.findByProjectKey(key).filter(x->"ACTIVE".equals(x.getStatus())).orElseThrow(()->error("PROJECT_NOT_FOUND","Project not found",HttpStatus.NOT_FOUND));if(!isAdmin()&&!members.existsByProjectIdAndUserIdAndStatus(p.getId(),actor,"ACTIVE"))throw error("PROJECT_NOT_FOUND","Project not found",HttpStatus.NOT_FOUND);return p;}
    private VirtualProjectEntity managed(String key,Long actor){VirtualProjectEntity p=access(key,actor);if(!manager(p.getId(),actor))throw error("PROJECT_MANAGE_FORBIDDEN","Project manager role required",HttpStatus.FORBIDDEN);return p;}
    private boolean manager(Long projectId,Long actor){if(isAdmin())return true;Integer creator=jdbc.queryForObject("select count(*) from virtual_project where id=? and created_by=?",Integer.class,projectId,actor);if(creator!=null&&creator>0)return true;return members.findByProjectIdAndUserId(projectId,actor).filter(m->"ACTIVE".equals(m.getStatus())&&Set.of("OWNER","MAINTAINER").contains(m.getRoleKey())).isPresent();}
    private boolean isAdmin(){Authentication a=SecurityContextHolder.getContext().getAuthentication();return a!=null&&a.getAuthorities().stream().anyMatch(x->"admin:identity".equals(x.getAuthority()));}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}

    public record WorkflowView(String projectKey,int progress,List<StageView> stages,List<MemberRoleView> memberRoles){}
    public record StageView(long id,String stageKey,int order,String availability,boolean enabled,String status,int cycleNo,int progress,String executorRole,String reviewerRole,List<String> dependencies,List<SkillView> skills,List<SubmissionView> submissions,List<ArtifactView> artifacts){}
    public record ArtifactView(Long documentId,String title,String documentType,Long revisionId,Integer revisionNo,java.time.Instant createdAt){}
    public record SkillView(String skillKey,String displayName,String lockedVersion){}
    public record MemberRoleView(Long userId,String displayName,String roleKey){}
    public record SubmissionView(Long id,int cycleNo,Long documentId,Long revisionId,Long submittedBy,String status,List<ReviewView> reviews){}
    public record ReviewView(Long reviewerId,String reviewerName,String decision,String comment){}
    public record UserSearchView(Long userId,String username,String displayName,String teamName){}
    public record ManagedSkill(String skillKey,Long versionId,String version,String sha256,String content){}
    public record AgentStageContext(Long stageId,String stageKey,String profileKey,List<ManagedSkill> skills){}
}
