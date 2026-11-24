package com.company.skillplatform.agent.application;

import com.company.skillplatform.knowledge.application.StructuredCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetrievalPlanStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public RetrievalPlanStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}

    public Map<String,Object> save(String runKey,RetrievalPlanner.Plan plan) {
        boolean general="GENERAL".equals(plan.strategy());
        var capabilities=new ArrayList<>(List.of("user.context.read","knowledge.search","wiki.read","wiki.search","knowledge.catalog.list","retrieval.plan.read","retrieval.task.refine"));
        boolean recommend=plan.tasks().stream().anyMatch(task->"RECOMMEND".equals(task.resourceKind()));
        if(plan.tasks().stream().anyMatch(task->"SKILL_READ".equals(task.resourceKind())))capabilities.addAll(List.of("skill.detail","skill.file.read"));
        if(recommend)capabilities.addAll(List.of("skill.search","skill.detail","skill.file.read","recommendation.submit"));
        boolean enterprise=plan.tasks().stream().anyMatch(task->"CATEGORY_PREFER".equals(task.resourceKind()));
        if(enterprise)capabilities.addAll(List.of("feishu.search","feishu.read"));
        if(general)capabilities.clear();
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",2);
        result.put("status","READY");
        var intents=List.of(general?"GENERAL_CHAT":"ROLE_CONTEXT");
        result.put("intents",intents);
        result.put("constraints",List.of());
        var artifacts=new LinkedHashSet<String>();
        artifacts.add("ANSWER");
        for(var task:plan.tasks())artifacts.add(switch(task.resourceKind()){
            case "SKILL" -> "SKILL_CATALOG";
            case "RECOMMEND" -> "SKILL";
            case "SKILL_READ" -> "SOURCE_LINK";
            case "ASDM-TOOL" -> "ASDM_CATALOG";
            default -> "WIKI_CATALOG";
        });
        result.put("allowedArtifacts",artifacts);
        result.put("allowedCapabilities",capabilities);
        result.put("policyKeys",general?List.of("CHAT"):recommend?List.of("WIKI","SKILL"):enterprise?List.of("KNOWLEDGE"):List.of("WIKI"));
        result.put("recommendSkills",recommend);
        result.put("retrieval",plan);
        db.update("""
                INSERT INTO agent_run_intent_plan(run_key,status,intents_json,constraints_json,allowed_artifacts_json,
                    allowed_capabilities_json,registry_snapshot_json,model_key,decision_latency_ms,schema_version,
                    retrieval_plan_json,model_advice_json,plan_revision)
                VALUES (?,'READY',?,'[]',?,?,'{}','deterministic',0,2,?,
                    '{"status":"SKIPPED"}',1)
                """,runKey,encode(intents),encode(artifacts),encode(capabilities),encode(result));
        return result;
    }

    public Optional<RetrievalPlanner.Plan> find(String runKey) {
        if(runKey==null)return Optional.empty();
        return db.query("""
                SELECT retrieval_plan_json FROM agent_run_intent_plan WHERE run_key=? AND schema_version=2
                """,(rs,row)->decode(rs.getString(1)),runKey).stream().findFirst();
    }

    @Transactional
    public Map<String,Object> refine(String runKey,String taskId,int revision,String category,String evidence) {
        if(evidence==null||evidence.isBlank()||evidence.length()>100)
            throw RetrievalScopeGuard.denied();
        var rows=db.query("""
                SELECT p.retrieval_plan_json,m.content FROM agent_run_intent_plan p
                JOIN agent_run r ON r.run_key=p.run_key
                JOIN agent_message m ON m.run_id=r.id AND m.role='USER'
                WHERE p.run_key=? AND p.plan_revision=? AND p.schema_version=2 AND r.status='RUNNING'
                """,(rs,index)->new String[]{rs.getString(1),rs.getString(2)},runKey,revision);
        if(rows.size()!=1||!rows.get(0)[1].contains(evidence))throw RetrievalScopeGuard.denied();
        String requiredText=switch(category==null?"":category){
            case "PROJECT_CONTEXT" -> "项目上下文";
            case "SKILL_TOOL" -> "Skill 与工具";
            case "FDE_WIKI" -> "FDE";
            case "INTERNAL_KNOWLEDGE" -> "内部知识";
            default -> throw RetrievalScopeGuard.denied();
        };
        if(!evidence.toLowerCase(Locale.ROOT).contains(requiredText.toLowerCase(Locale.ROOT)))throw RetrievalScopeGuard.denied();
        var plan=decode(rows.get(0)[0]);
        var task=plan.tasks().stream().filter(item->item.taskId().equals(taskId)&&"DOCUMENT".equals(item.resourceKind()))
                .findFirst().orElseThrow(RetrievalScopeGuard::denied);
        if(task.category()!=null&&!task.category().equals(category))throw RetrievalScopeGuard.denied();
        var narrowed=new RetrievalPlanner.Task(task.taskId(),task.roleId(),task.resourceKind(),category,task.stage());
        var tasks=plan.tasks().stream().map(item->item.taskId().equals(taskId)?narrowed:item).toList();
        var updated=new RetrievalPlanner.Plan(plan.strategy(),tasks.get(0),plan.roles(),tasks);
        int count=db.update("""
                UPDATE agent_run_intent_plan SET retrieval_plan_json=JSON_SET(retrieval_plan_json,'$.retrieval',CAST(? AS JSON)),
                    plan_revision=plan_revision+1 WHERE run_key=? AND plan_revision=? AND schema_version=2
                """,encode(updated),runKey,revision);
        if(count!=1)throw RetrievalScopeGuard.denied();
        return Map.of("revision",revision+1,"retrieval",updated);
    }

    public int revision(String runKey) {
        return Optional.ofNullable(db.queryForObject("SELECT plan_revision FROM agent_run_intent_plan WHERE run_key=?",
                Integer.class,runKey)).orElse(0);
    }

    public Optional<RetrievalPlanner.Plan> priorCatalog(String runKey) {
        return db.query("""
                SELECT p.retrieval_plan_json FROM agent_run current_run
                JOIN agent_run prev ON prev.session_id=current_run.session_id AND prev.run_no=current_run.run_no-1
                JOIN agent_run_intent_plan p ON p.run_key=prev.run_key AND p.schema_version=2
                WHERE current_run.run_key=? AND prev.status='SUCCEEDED'
                  AND EXISTS (SELECT 1 FROM agent_run_retrieval_evidence evidence WHERE evidence.run_key=prev.run_key)
                """,(rs,row)->decode(rs.getString(1)),runKey).stream().findFirst();
    }

    @Transactional
    public void record(String runKey,RetrievalPlanner.Task task,StructuredCatalogService.Page page) {
        for(var item:page.items())db.update("""
                INSERT INTO agent_run_retrieval_evidence(run_key,task_id,evidence_kind,object_key,version_key,audience,metadata_json)
                VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE metadata_json=VALUES(metadata_json)
                """,runKey,task.taskId(),task.resourceKind(),String.valueOf(item.get("id")),String.valueOf(item.get("versionId")),
                String.valueOf(item.getOrDefault("audience","")),encode(item));
    }

    public Optional<SkillReference> priorSkill(String runKey,int ordinal) {
        if(ordinal<1)return Optional.empty();
        return db.query("""
                SELECT e.object_key,e.version_key,JSON_UNQUOTE(JSON_EXTRACT(e.metadata_json,'$.skillKey'))
                FROM agent_run current_run
                JOIN agent_run prev ON prev.session_id=current_run.session_id AND prev.run_no=current_run.run_no-1
                JOIN agent_run_retrieval_evidence e ON e.run_key=prev.run_key AND e.evidence_kind='SKILL' AND e.task_id='t1'
                WHERE current_run.run_key=? AND prev.status='SUCCEEDED' GROUP BY e.object_key,e.version_key,e.metadata_json
                ORDER BY MIN(e.id) LIMIT 1 OFFSET ?
                """,(rs,row)->new SkillReference(Long.parseLong(rs.getString(1)),Long.parseLong(rs.getString(2)),
                        rs.getString(3)),runKey,ordinal-1).stream().findFirst();
    }

    public List<SkillReference> namedSkills(String normalizedQuery) {
        return db.query("""
                SELECT s.id,sv.id,s.skill_key,LOWER(s.display_name)
                  FROM skill s JOIN skill_version sv ON sv.id=s.latest_published_version_id
                  WHERE s.status='ACTIVE' AND s.scope_type='PLATFORM'
                    AND sv.lifecycle_status IN ('PUBLISHED','DEPRECATED')
                    AND (LOCATE(LOWER(s.skill_key),?)>0 OR (CHAR_LENGTH(s.display_name)>=3 AND LOCATE(LOWER(s.display_name),?)>0))
                """,(rs,row)->{
                    var key=rs.getString(3);
                    String pattern="(?<![a-z0-9_.-])"+java.util.regex.Pattern.quote(key.toLowerCase(Locale.ROOT))+"(?![a-z0-9_.-])";
                    if(!java.util.regex.Pattern.compile(pattern).matcher(normalizedQuery).find()
                            &&(rs.getString(4).length()<3||!normalizedQuery.contains(rs.getString(4))))return null;
                    return new SkillReference(rs.getLong(1),rs.getLong(2),key);
                },normalizedQuery,normalizedQuery).stream().filter(Objects::nonNull).toList();
    }

    public record SkillReference(long id,long versionId,String skillKey) {}

    public Map<Long,Long> skillVersions(String runKey,String taskId) {
        Map<Long,Long> result=new LinkedHashMap<>();
        db.query("""
                SELECT object_key,version_key FROM agent_run_retrieval_evidence
                WHERE run_key=? AND task_id=? AND evidence_kind='SKILL' ORDER BY id
                """,rs->{result.putIfAbsent(Long.valueOf(rs.getString(1)),Long.valueOf(rs.getString(2)));},runKey,taskId);
        return result;
    }

    private RetrievalPlanner.Plan decode(String value) {
        try{return json.treeToValue(json.readTree(value).path("retrieval"),RetrievalPlanner.Plan.class);}
        catch(Exception failure){throw new IllegalStateException("Invalid persisted retrieval plan",failure);}
    }

    private String encode(Object value) {
        try{return json.writeValueAsString(value);}
        catch(Exception failure){throw new IllegalStateException("Cannot serialize retrieval plan",failure);}
    }
}
