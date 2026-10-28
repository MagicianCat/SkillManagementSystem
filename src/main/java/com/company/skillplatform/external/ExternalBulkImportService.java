package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.wiki.application.WikiDocumentReviewService;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.workflow.application.WorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExternalBulkImportService {
    private static final Logger log = LoggerFactory.getLogger(ExternalBulkImportService.class);
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final ExternalResourceAdminService imports;
    private final SkillService skills;
    private final WorkflowService workflow;
    private final WikiDocumentService wiki;
    private final WikiDocumentReviewService wikiReviews;
    private final NotificationService notifications;
    private final TransactionTemplate transactions;

    public ExternalBulkImportService(JdbcTemplate db, ObjectMapper mapper, ExternalResourceAdminService imports,
            SkillService skills, WorkflowService workflow, WikiDocumentService wiki,
            WikiDocumentReviewService wikiReviews, NotificationService notifications, TransactionTemplate transactions) {
        this.db=db; this.mapper=mapper; this.imports=imports; this.skills=skills; this.workflow=workflow;
        this.wiki=wiki; this.wikiReviews=wikiReviews; this.notifications=notifications; this.transactions=transactions;
    }

    public long enqueueSkills(long roleId, List<Long> resourceIds, long actorId) {
        if (resourceIds == null || resourceIds.isEmpty() || resourceIds.stream().anyMatch(id -> id == null || id <= 0))
            throw bad("EXTERNAL_SKILL_SELECTION_REQUIRED", "请至少选择一个 Skill");
        return enqueue("SKILL", roleId, null, null, false, resourceIds, null, actorId);
    }
    public long enqueueWikis(long roleId, Long teamId, Long parentWikiId, boolean linkRoleSkills, List<WikiSelection> selections, long actorId) {
        if (teamId != null && teamId <= 0) throw bad("WIKI_TEAM_INVALID", "目标团队无效");
        if (selections == null || selections.isEmpty() || selections.stream().anyMatch(selection ->
                selection == null || selection.resourceId() == null || selection.resourceId() <= 0 ||
                selection.paths() == null || selection.paths().isEmpty() ||
                selection.paths().stream().anyMatch(path -> path == null || path.isBlank()) ||
                new HashSet<>(selection.paths()).size() != selection.paths().size()))
            throw bad("EXTERNAL_WIKI_SELECTION_INVALID", "请为每个选中的 ZIP 至少选择一篇文档，且不能重复");
        Set<Long> selectedIds=selections.stream().map(WikiSelection::resourceId).collect(Collectors.toSet());
        if (selectedIds.size()!=selections.size()) throw bad("EXTERNAL_WIKI_SELECTION_INVALID", "ZIP 选择包含重复资源");
        if (parentWikiId != null) {
            var parent = wiki.get(parentWikiId, actorId);
            boolean scopeMatches=teamId==null?parent.platformVisible():java.util.Objects.equals(teamId,parent.teamId());
            if (!scopeMatches || !"SKILL_GUIDE".equals(parent.documentType()))
                throw bad("WIKI_PARENT_SCOPE_INVALID", "父 Wiki 必须在目标范围内可见");
        }
        return enqueue("DOCUMENT", roleId, teamId, parentWikiId, linkRoleSkills, null, selections, actorId);
    }

    public record WikiSelection(Long resourceId,List<String> paths) {}

    private long enqueue(String kind, long roleId, Long teamId, Long parentWikiId, boolean linkRoleSkills, List<Long> resourceIds, List<WikiSelection> wikiSelections, long actorId) {
        Integer role = db.queryForObject("SELECT COUNT(*) FROM external_role WHERE id=? AND status='ACTIVE'", Integer.class, roleId);
        if (role == null || role == 0) throw bad("EXTERNAL_ROLE_NOT_FOUND", "外部角色不存在或未启用");
        Integer running=db.queryForObject("SELECT COUNT(*) FROM external_import_job WHERE role_id=? AND kind=? AND team_id <=> ? AND status IN ('PENDING','RUNNING')",Integer.class,roleId,kind,teamId);
        if (running!=null && running>0) throw bad("EXTERNAL_IMPORT_ALREADY_RUNNING","当前角色已有同类导入任务在执行");
        List<Map<String,Object>> snapshot = db.queryForList("SELECT r.id resourceId,r.external_resource_id externalResourceId,r.source_system sourceSystem," +
                "r.name,r.description,r.current_version_id versionId FROM external_role_resource rr " +
                "JOIN external_resource r ON r.id=rr.resource_id WHERE rr.role_id=? AND r.resource_type=? AND r.current_version_id IS NOT NULL ORDER BY r.id", roleId, kind);
        if (resourceIds != null) {
            Set<Long> selected = new HashSet<>(resourceIds);
            if (selected.size() != resourceIds.size()) throw bad("EXTERNAL_SKILL_SELECTION_INVALID", "Skill 选择包含重复资源");
            snapshot = snapshot.stream().filter(row -> selected.contains(((Number)row.get("resourceId")).longValue())).toList();
            if (snapshot.size() != selected.size()) throw bad("EXTERNAL_SKILL_SELECTION_INVALID", "所选 Skill 不属于当前角色或没有可导入版本，请刷新后重试");
        }
        if (wikiSelections != null) {
            Map<Long,List<String>> pathsByResource=wikiSelections.stream().collect(Collectors.toMap(WikiSelection::resourceId,WikiSelection::paths));
            snapshot = snapshot.stream().filter(row -> pathsByResource.containsKey(((Number)row.get("resourceId")).longValue())).map(row -> {
                Map<String,Object> selectedRow=new java.util.LinkedHashMap<>(row);
                selectedRow.put("selectedPaths",pathsByResource.get(((Number)row.get("resourceId")).longValue()));
                return selectedRow;
            }).toList();
            if (snapshot.size()!=pathsByResource.size()) throw bad("EXTERNAL_WIKI_SELECTION_INVALID", "所选 ZIP 不属于当前角色或没有可导入版本，请刷新后重试");
        }
        if (snapshot.isEmpty()) throw bad("EXTERNAL_RESOURCES_EMPTY", "当前角色没有可导入的资源");
        String json;
        try { json=mapper.writeValueAsString(snapshot); } catch (Exception ex) { throw new IllegalStateException(ex); }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO external_import_job(kind,role_id,team_id,parent_wiki_id,link_role_skills,status,operator_id,snapshot_json) VALUES(?,?,?,?,?,'PENDING',?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, kind); statement.setLong(2, roleId);
            if (teamId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, teamId);
            if (parentWikiId == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, parentWikiId);
            statement.setBoolean(5, linkRoleSkills); statement.setLong(6, actorId); statement.setString(7, json);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public Map<String,Object> job(long id) {
        List<Map<String,Object>> jobs=db.queryForList("SELECT id,kind,role_id roleId,team_id teamId,parent_wiki_id parentWikiId,link_role_skills linkRoleSkills,status,total_count totalCount,success_count successCount,failure_count failureCount,error_message errorMessage,created_at createdAt,started_at startedAt,finished_at finishedAt FROM external_import_job WHERE id=?", id);
        if (jobs.isEmpty()) throw bad("EXTERNAL_IMPORT_JOB_NOT_FOUND", "导入任务不存在");
        return Map.of("job", jobs.get(0), "items", db.queryForList("SELECT resource_id resourceId,version_id versionId,entry_path entryPath,status,target_id targetId,error_code errorCode,error_message errorMessage,created_at createdAt FROM external_import_job_item WHERE job_id=? ORDER BY id", id));
    }

    @Scheduled(fixedDelayString="${integration.external.bulk.poll-ms:3000}")
    public void poll() {
        List<Long> pending = db.query("SELECT id FROM external_import_job WHERE status='PENDING' OR (status='RUNNING' AND heartbeat_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)) ORDER BY id LIMIT 1", (rs,n)->rs.getLong(1));
        if (pending.isEmpty()) return;
        long id=pending.get(0);
        if (db.update("UPDATE external_import_job SET status='RUNNING',started_at=COALESCE(started_at,NOW(3)),heartbeat_at=NOW(3) WHERE id=? AND (status='PENDING' OR (status='RUNNING' AND heartbeat_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)))",id)!=1) return;
        Map<String,Object> job=db.queryForMap("SELECT * FROM external_import_job WHERE id=?",id);
        long actor=((Number)job.get("operator_id")).longValue();
        SecurityContext previous=SecurityContextHolder.getContext();
        SecurityContext context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(
                new SimpleGrantedAuthority("admin:identity"),new SimpleGrantedAuthority("skill:upload"),
                new SimpleGrantedAuthority("skill:edit"),new SimpleGrantedAuthority("skill:browse"))));
        SecurityContextHolder.setContext(context);
        try {
            List<Map<String,Object>> resources=mapper.readValue(String.valueOf(job.get("snapshot_json")), new TypeReference<>() {});
            for (Map<String,Object> resource:resources) {
                db.update("UPDATE external_import_job SET heartbeat_at=NOW(3) WHERE id=?",id);
                if ("SKILL".equals(job.get("kind"))) processSkill(id,actor,resource);
                else processWikiResource(id,actor,job,resource);
            }
            int total=db.queryForObject("SELECT COUNT(*) FROM external_import_job_item WHERE job_id=?",Integer.class,id);
            int success=db.queryForObject("SELECT COUNT(*) FROM external_import_job_item WHERE job_id=? AND status='SUCCESS'",Integer.class,id);
            int failure=total-success;
            db.update("UPDATE external_import_job SET status='COMPLETED',total_count=?,success_count=?,failure_count=?,finished_at=NOW(3),heartbeat_at=NOW(3) WHERE id=?",total,success,failure,id);
            String summary=String.join("；",db.query("SELECT CONCAT(COALESCE(entry_path,resource_id),'：',LEFT(COALESCE(error_message,error_code,'导入失败'),160)) FROM external_import_job_item WHERE job_id=? AND status='FAILED' ORDER BY id LIMIT 5",(rs,n)->rs.getString(1),id));
            notifyCompleted(actor,id,String.valueOf(job.get("kind")),success,failure,summary);
        } catch (Exception ex) {
            log.error("event=external.bulk.failed jobId={}",id,ex);
            db.update("UPDATE external_import_job SET status='FAILED',failure_count=GREATEST(failure_count,1),total_count=GREATEST(total_count,1),error_message=?,finished_at=NOW(3) WHERE id=?",safe(ex.getMessage()),id);
            notifyCompleted(actor,id,String.valueOf(job.get("kind")),0,1,safe(ex.getMessage()));
        } finally { SecurityContextHolder.setContext(previous); }
    }

    private void processSkill(long jobId,long actor,Map<String,Object> resource) {
        long version=((Number)resource.get("versionId")).longValue();
        long resourceId=((Number)resource.get("resourceId")).longValue();
        if (completed(jobId,resourceId,version,null)) return;
        try {
            Long target=transactions.execute(status -> {
                var parsed=imports.prepareSkill(version);
                String name=String.valueOf(resource.get("name"));
                String key=parsed.skillKey()==null?name:parsed.skillKey();
                if (!key.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) throw bad("INVALID_SKILL_KEY", "SKILL.md name 与资源名称均不是合法 Skill Key");
                var preparation=parsed.skillKey()==null?imports.prepareSkill(version,key):parsed;
                if (!preparation.exists()) {
                    String subStage=db.queryForObject("SELECT sub_stage_key FROM external_resource_version WHERE id=?",String.class,version);
                    if (subStage==null || subStage.isBlank()) throw bad("MISSING_SUB_STAGE","新 Skill 缺少二级研发分类，请推送 subStageKey 后重试");
                }
                if (preparation.activeDraftVersionId()!=null) {
                    var currentDraft=skills.version(preparation.activeDraftVersionId());
                    if (currentDraft.getLifecycleStatus()==com.company.skillplatform.version.domain.LifecycleStatus.REVIEWING)
                        workflow.cancelPendingExternal(currentDraft.getId(),actor,"external-bulk-"+jobId);
                    else if (currentDraft.getLifecycleStatus()==com.company.skillplatform.version.domain.LifecycleStatus.APPROVED)
                        workflow.withdraw(currentDraft.getId(),currentDraft.getVersionNo(),"外部资源更新，替换已审核草稿",actor,"external-bulk-"+jobId);
                }
                String description=resource.get("description")==null||String.valueOf(resource.get("description")).isBlank()?name:String.valueOf(resource.get("description"));
                var imported=imports.importSkill(version,new ExternalResourceAdminService.SkillImportRequest(key,name,description,null,null,true,true),actor,"external-bulk-"+jobId);
                var draft=skills.version(imported.draftVersionId());
                workflow.submit(imported.draftVersionId(),draft.getVersionNo(),"外部资源批量导入，提交审核",actor,"external-bulk-"+jobId);
                return imported.skillId();
            });
            record(jobId,resourceId,version,null,"SUCCESS",target,null,null);
        } catch (RuntimeException ex) { record(jobId,resourceId,version,null,"FAILED",null,code(ex),safe(ex.getMessage())); }
    }

    private void processWikiResource(long jobId,long actor,Map<String,Object> job,Map<String,Object> resource) {
        long version=((Number)resource.get("versionId")).longValue();
        long resourceId=((Number)resource.get("resourceId")).longValue();
        Map<String,String> contents;
        try { contents=imports.documentContents(version); }
        catch (RuntimeException ex) { record(jobId,resourceId,version,null,"FAILED",null,code(ex),safe(ex.getMessage())); return; }
        @SuppressWarnings("unchecked") List<String> selectedPaths=(List<String>)resource.get("selectedPaths");
        if (selectedPaths==null || selectedPaths.isEmpty()) { record(jobId,resourceId,version,null,"FAILED",null,"EXTERNAL_WIKI_SELECTION_INVALID","任务没有选中任何文档"); return; }
        Long roleId=((Number)job.get("role_id")).longValue();
        Long teamId=job.get("team_id")==null?null:((Number)job.get("team_id")).longValue();
        Long parentId=job.get("parent_wiki_id")==null?null:((Number)job.get("parent_wiki_id")).longValue();
        for (String path:selectedPaths) {
            if (completed(jobId,resourceId,version,path)) continue;
            String content=contents.get(path);
            if (content==null) { record(jobId,resourceId,version,path,"FAILED",null,"EXTERNAL_WIKI_PATH_INVALID","所选文档不在 ZIP 中"); continue; }
            try {
                Long target=transactions.execute(status -> {
                    List<Long> skillIds=List.of();
                    Object requestedLinks=job.get("link_role_skills");
                    if (Boolean.TRUE.equals(requestedLinks) || requestedLinks instanceof Number number && number.intValue()!=0) {
                        var ready=imports.readiness(roleId);
                        if (ready.skillIds().isEmpty()) throw bad("ROLE_SKILLS_NOT_READY","当前角色尚无已导入平台的 Skill");
                        skillIds=ready.skillIds();
                    }
                    String source=String.valueOf(resource.get("sourceSystem"));
                    String externalId=String.valueOf(resource.get("externalResourceId"));
                    List<Long> existing=db.query("SELECT id FROM wiki_document WHERE source_system=? AND source_resource_id=? AND source_entry_path=? AND team_id <=> ? AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getLong(1),source,externalId,path,teamId);
                    String title=path.substring(path.lastIndexOf('/')+1).replaceFirst("(?i)\\.md$","");
                    WikiDocumentService.DocumentView document;
                    if (existing.isEmpty()) {
                        document=wiki.create(new WikiDocumentService.CreateCommand(title,"SKILL_GUIDE",teamId,skillIds,content,parentId),actor,"external-bulk-"+jobId);
                        wiki.markExternalSource(document.id(),source,externalId,path);
                    } else {
                        var current=wiki.get(existing.get(0),actor);
                        if (current.pendingPlatformReviewId()!=null)
                            wikiReviews.withdraw(current.pendingPlatformReviewId(),"外部资源更新，替换待审修订",actor,"external-bulk-"+jobId);
                        document=wiki.update(current.id(),new WikiDocumentService.UpdateCommand(title,content,current.versionNo(),skillIds,parentId,true),actor,"external-bulk-"+jobId);
                    }
                    if (!document.platformVisible()) wikiReviews.submit(document.id(),document.versionNo(),"外部资源批量导入，提交审核",actor,"external-bulk-"+jobId);
                    db.update("INSERT INTO external_import_record(resource_version_id,external_role_id,entry_path,target_type,target_id,action,result,operator_id) VALUES(?,?,?,'WIKI',?,?,'SUCCESS',?)",version,roleId,path,document.id(),existing.isEmpty()?"IMPORT":"UPDATE",actor);
                    return document.id();
                });
                record(jobId,resourceId,version,path,"SUCCESS",target,null,null);
            } catch (RuntimeException ex) { record(jobId,resourceId,version,path,"FAILED",null,code(ex),safe(ex.getMessage())); }
        }
    }

    private boolean completed(long jobId,long resourceId,long version,String path) {
        Integer count=db.queryForObject("SELECT COUNT(*) FROM external_import_job_item WHERE job_id=? AND resource_id=? AND version_id=? AND entry_path <=> ? AND status='SUCCESS'",Integer.class,jobId,resourceId,version,path);
        return count!=null&&count>0;
    }
    private void record(long jobId,long resourceId,long version,String path,String state,Long target,String errorCode,String errorMessage) {
        db.update("DELETE FROM external_import_job_item WHERE job_id=? AND resource_id=? AND version_id=? AND entry_path <=> ? AND status='FAILED'",jobId,resourceId,version,path);
        db.update("INSERT INTO external_import_job_item(job_id,resource_id,version_id,entry_path,status,target_id,error_code,error_message) VALUES(?,?,?,?,?,?,?,?)",jobId,resourceId,version,path,state,target,errorCode,errorMessage);
    }
    private void notifyCompleted(long actor,long jobId,String kind,int success,int failure,String summary) {
        try { notifications.externalImportCompleted(actor,jobId,kind,success,failure,summary); }
        catch (RuntimeException ex) { log.error("event=external.bulk.notification_failed jobId={}",jobId,ex); }
    }
    private String code(RuntimeException ex) { return ex instanceof BusinessException business?business.getCode():"IMPORT_ERROR"; }
    private String safe(String value) { return value==null?"操作失败":value.substring(0,Math.min(value.length(),2048)); }
    private BusinessException bad(String code,String message) { return new BusinessException(code,message,HttpStatus.BAD_REQUEST); }
}
