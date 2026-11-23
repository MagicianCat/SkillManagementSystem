package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.skill.application.SkillService;
import com.company.skillplatform.wiki.application.WikiDocumentReviewService;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.domain.WikiKnowledgeCategory;
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
    private AsdmToolImportService asdm;
    @org.springframework.beans.factory.annotation.Autowired public void setAsdm(AsdmToolImportService value) { this.asdm=value; }
    private ExternalRoleWikiParentService roleWikiParents;
    @org.springframework.beans.factory.annotation.Autowired public void setRoleWikiParents(ExternalRoleWikiParentService value) { this.roleWikiParents=value; }

    public ExternalBulkImportService(JdbcTemplate db, ObjectMapper mapper, ExternalResourceAdminService imports,
            SkillService skills, WorkflowService workflow, WikiDocumentService wiki,
            WikiDocumentReviewService wikiReviews, NotificationService notifications, TransactionTemplate transactions) {
        this.db=db; this.mapper=mapper; this.imports=imports; this.skills=skills; this.workflow=workflow;
        this.wiki=wiki; this.wikiReviews=wikiReviews; this.notifications=notifications; this.transactions=transactions;
    }

    public long enqueueSkills(long roleId, List<Long> resourceIds, long actorId) {
        if (resourceIds == null || resourceIds.isEmpty() || resourceIds.stream().anyMatch(id -> id == null || id <= 0))
            throw bad("EXTERNAL_SKILL_SELECTION_REQUIRED", "请至少选择一个 Skill");
        return enqueue("SKILL", "REVIEW", roleId, null, null, false, null, resourceIds, null, actorId);
    }
    public long enqueueSkillsDirect(long roleId, List<Long> resourceIds, long actorId) {
        if (resourceIds == null || resourceIds.isEmpty() || resourceIds.stream().anyMatch(id -> id == null || id <= 0))
            throw bad("EXTERNAL_SKILL_SELECTION_REQUIRED", "请至少选择一个 Skill");
        Integer allowed=db.queryForObject("SELECT COUNT(*) FROM iam_user u JOIN iam_user_role ur ON ur.user_id=u.id JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id JOIN iam_permission p ON p.id=rp.permission_id WHERE u.id=? AND u.status='ACTIVE' AND r.role_key='ADMIN' AND r.status='ACTIVE' AND p.permission_key='external:resource:skill:publish:direct'",Integer.class,actorId);
        if (allowed == null || allowed == 0) throw new org.springframework.security.access.AccessDeniedException("Active super administrator with direct publication permission required");
        return enqueue("SKILL", "DIRECT_PLATFORM", roleId, null, null, false, null, resourceIds, null, actorId);
    }
    public long enqueueAsdm(long roleId,long resourceId,long versionId,long actorId) {
        Integer valid=db.queryForObject("SELECT COUNT(*) FROM external_role_resource rr JOIN external_role role ON role.id=rr.role_id JOIN external_resource r ON r.id=rr.resource_id WHERE rr.role_id=? AND rr.resource_id=? AND role.status='ACTIVE' AND r.resource_type='ASDM-TOOL' AND r.current_version_id=?",Integer.class,roleId,resourceId,versionId);
        if(valid==null||valid!=1) throw bad("EXTERNAL_ASDM_VERSION_INVALID","ASDM 资源版本已更新或不属于当前角色");
        var state=asdm.status(versionId);
        long job=enqueue("ASDM_TOOL","REVIEW",roleId,null,null,false,null,List.of(resourceId),null,actorId);
        db.update("UPDATE external_import_job SET snapshot_json=JSON_SET(snapshot_json,'$[0].confirmUpdate',CAST(? AS JSON),'$[0].expectedWikiVersionNo',CAST(? AS JSON)) WHERE id=?",
                state.wikiActive()?"true":"false",state.wikiVersionNo()==null?"null":String.valueOf(state.wikiVersionNo()),job);
        return job;
    }
    public long enqueueWikis(long roleId, Long teamId, Long parentWikiId, boolean linkRoleSkills, String knowledgeCategory, List<WikiSelection> selections, long actorId) {
        String category = requiredWikiCategory(knowledgeCategory);
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
            roleWikiParents.requireEligible(roleId,teamId,parentWikiId);
            var parent = wiki.get(parentWikiId, actorId);
            boolean scopeMatches=java.util.Objects.equals(teamId,parent.teamId());
            if (!scopeMatches)
                throw bad("WIKI_PARENT_SCOPE_INVALID", "父 Wiki 必须在目标范围内可见");
        }
        return enqueue("DOCUMENT", "REVIEW", roleId, teamId, parentWikiId, linkRoleSkills, category, null, selections, actorId);
    }

    static String requiredWikiCategory(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("EXTERNAL_WIKI_CATEGORY_REQUIRED", "请选择本批文档的知识分类", HttpStatus.BAD_REQUEST);
        WikiKnowledgeCategory category = WikiKnowledgeCategory.parse(value, null);
        if (category == WikiKnowledgeCategory.FDE_WIKI) throw new BusinessException("WIKI_KNOWLEDGE_CATEGORY_INVALID", "外部文档导入不支持 FDE 专区分类", HttpStatus.BAD_REQUEST);
        return category.name();
    }

    public record WikiSelection(Long resourceId,List<String> paths) {}

    private long enqueue(String kind, String publicationMode, long roleId, Long teamId, Long parentWikiId, boolean linkRoleSkills, String knowledgeCategory, List<Long> resourceIds, List<WikiSelection> wikiSelections, long actorId) {
        Integer role = db.queryForObject("SELECT COUNT(*) FROM external_role WHERE id=? AND status='ACTIVE'", Integer.class, roleId);
        if (role == null || role == 0) throw bad("EXTERNAL_ROLE_NOT_FOUND", "外部角色不存在或未启用");
        Integer running="ASDM_TOOL".equals(kind)?0:db.queryForObject("SELECT COUNT(*) FROM external_import_job WHERE role_id=? AND kind=? AND team_id <=> ? AND status IN ('PENDING','RUNNING')",Integer.class,roleId,kind,teamId);
        if (running!=null && running>0) throw bad("EXTERNAL_IMPORT_ALREADY_RUNNING","当前角色已有同类导入任务在执行");
        String resourceType="ASDM_TOOL".equals(kind)?"ASDM-TOOL":kind;
        List<Map<String,Object>> snapshot = db.queryForList("SELECT r.id resourceId,r.external_resource_id externalResourceId,r.source_system sourceSystem," +
                "r.name,r.description,r.current_version_id versionId FROM external_role_resource rr " +
                "JOIN external_resource r ON r.id=rr.resource_id WHERE rr.role_id=? AND r.resource_type=? AND r.current_version_id IS NOT NULL ORDER BY r.id", roleId, resourceType);
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
            var statement = connection.prepareStatement("INSERT INTO external_import_job(kind,role_id,team_id,parent_wiki_id,link_role_skills,status,operator_id,snapshot_json,knowledge_category,publication_mode) VALUES(?,?,?,?,?,'PENDING',?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, kind); statement.setLong(2, roleId);
            if (teamId == null) statement.setNull(3, java.sql.Types.BIGINT); else statement.setLong(3, teamId);
            if (parentWikiId == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, parentWikiId);
            statement.setBoolean(5, linkRoleSkills); statement.setLong(6, actorId); statement.setString(7, json); statement.setString(8, knowledgeCategory); statement.setString(9, publicationMode);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public Map<String,Object> job(long id) {
        List<Map<String,Object>> jobs=db.queryForList("SELECT id,kind,publication_mode publicationMode,role_id roleId,team_id teamId,parent_wiki_id parentWikiId,link_role_skills linkRoleSkills,knowledge_category knowledgeCategory,status,total_count totalCount,success_count successCount,failure_count failureCount,error_message errorMessage,created_at createdAt,started_at startedAt,finished_at finishedAt FROM external_import_job WHERE id=?", id);
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
                new SimpleGrantedAuthority("skill:edit"),new SimpleGrantedAuthority("skill:browse"),
                new SimpleGrantedAuthority("external:resource:skill:publish:direct"))));
        SecurityContextHolder.setContext(context);
        try {
            String wikiCategory = "DOCUMENT".equals(job.get("kind")) ? requiredWikiCategory((String) job.get("knowledge_category")) : null;
            List<Map<String,Object>> resources=mapper.readValue(String.valueOf(job.get("snapshot_json")), new TypeReference<>() {});
            for (Map<String,Object> resource:resources) {
                db.update("UPDATE external_import_job SET heartbeat_at=NOW(3) WHERE id=?",id);
                if ("SKILL".equals(job.get("kind"))) processSkill(id,actor,resource,"DIRECT_PLATFORM".equals(job.get("publication_mode")));
                else if ("ASDM_TOOL".equals(job.get("kind"))) processAsdm(id,actor,resource);
                else processWikiResource(id,actor,job,resource,wikiCategory);
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

    private void processSkill(long jobId,long actor,Map<String,Object> resource,boolean direct) {
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
                if (direct) {
                    var built=workflow.publishExternalDirect(imported.draftVersionId(),"external-direct-"+jobId+"-"+version,actor,"external-bulk-"+jobId);
                    if (!"SUCCEEDED".equals(built.status())) throw new IllegalStateException("Skill 构建失败："+built.errorMessage());
                } else workflow.submit(imported.draftVersionId(),draft.getVersionNo(),"外部资源批量导入，提交审核",actor,"external-bulk-"+jobId);
                return imported.skillId();
            });
            record(jobId,resourceId,version,null,"SUCCESS",target,null,null);
        } catch (RuntimeException ex) { record(jobId,resourceId,version,null,"FAILED",null,code(ex),safe(ex.getMessage())); }
    }

    private void processWikiResource(long jobId,long actor,Map<String,Object> job,Map<String,Object> resource,String knowledgeCategory) {
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
                    if(parentId!=null) roleWikiParents.requireEligible(roleId,teamId,parentId);
                    List<Long> skillIds=List.of();
                    Object requestedLinks=job.get("link_role_skills");
                    if (Boolean.TRUE.equals(requestedLinks) || requestedLinks instanceof Number number && number.intValue()!=0) {
                        var ready=imports.readiness(roleId);
                        if (!ready.ready()) throw bad("ROLE_SKILLS_NOT_READY","当前角色的全部 Skill 尚未成功发布到全平台");
                        skillIds=ready.skillIds();
                    }
                    String source=String.valueOf(resource.get("sourceSystem"));
                    String externalId=String.valueOf(resource.get("externalResourceId"));
                    List<Long> existing=db.query("SELECT id FROM wiki_document WHERE source_system=? AND source_resource_id=? AND source_entry_path=? AND team_id <=> ? AND status='ACTIVE' FOR UPDATE",(rs,n)->rs.getLong(1),source,externalId,path,teamId);
                    String title=path.substring(path.lastIndexOf('/')+1).replaceFirst("(?i)\\.md$","");
                    WikiDocumentService.DocumentView document;
                    if (existing.isEmpty()) {
                        document=wiki.create(new WikiDocumentService.CreateCommand(title,teamId,skillIds,content,parentId,knowledgeCategory),actor,"external-bulk-"+jobId);
                        wiki.markExternalSource(document.id(),source,externalId,path);
                    } else {
                        var current=wiki.get(existing.get(0),actor);
                        if (current.pendingPlatformReviewId()!=null)
                            wikiReviews.withdraw(current.pendingPlatformReviewId(),"外部资源更新，替换待审修订",actor,"external-bulk-"+jobId);
                        document=wiki.update(current.id(),new WikiDocumentService.UpdateCommand(title,content,current.versionNo(),skillIds,parentId,true,knowledgeCategory),actor,"external-bulk-"+jobId);
                    }
                    if (teamId != null) wikiReviews.submit(document.id(),document.versionNo(),"外部资源批量导入，提交审核",actor,"external-bulk-"+jobId);
                    db.update("INSERT INTO external_import_record(resource_version_id,external_role_id,entry_path,target_type,target_id,action,result,operator_id) VALUES(?,?,?,'WIKI',?,?,'SUCCESS',?)",version,roleId,path,document.id(),existing.isEmpty()?"IMPORT":"UPDATE",actor);
                    return document.id();
                });
                record(jobId,resourceId,version,path,"SUCCESS",target,null,null);
            } catch (RuntimeException ex) { record(jobId,resourceId,version,path,"FAILED",null,code(ex),safe(ex.getMessage())); }
        }
    }

    private void processAsdm(long jobId,long actor,Map<String,Object> resource) {
        long resourceId=((Number)resource.get("resourceId")).longValue();
        long version=((Number)resource.get("versionId")).longValue();
        if(completed(jobId,resourceId,version,null)) return;
        try {
            Long current=db.queryForObject("SELECT current_version_id FROM external_resource WHERE id=?",Long.class,resourceId);
            if(!java.util.Objects.equals(current,version)) throw bad("EXTERNAL_ASDM_VERSION_INVALID","ASDM 资源已有新版本，请使用最新卡片");
            boolean confirmed=Boolean.TRUE.equals(resource.get("confirmUpdate"));
            Integer expected=resource.get("expectedWikiVersionNo") instanceof Number number?number.intValue():null;
            var result=asdm.importWiki(version,new AsdmToolImportService.ImportCommand(confirmed,expected),actor,"external-bulk-"+jobId);
            record(jobId,resourceId,version,null,"SUCCESS",result.wikiDocumentId(),null,null);
        } catch(RuntimeException ex) { record(jobId,resourceId,version,null,"FAILED",null,code(ex),safe(ex.getMessage())); }
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
