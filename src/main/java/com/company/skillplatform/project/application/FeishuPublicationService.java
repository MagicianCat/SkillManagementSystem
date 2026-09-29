package com.company.skillplatform.project.application;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.notification.domain.NotificationType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, best-effort publisher for accepted virtual-project artifacts. */
@Service
public class FeishuPublicationService {
    private static final Logger log = LoggerFactory.getLogger(FeishuPublicationService.class);
    private final JdbcTemplate jdbc;
    private final FeishuDocumentMcpProxy feishu;
    private final NotificationService notifications;
    private final ObjectMapper json;

    public FeishuPublicationService(JdbcTemplate jdbc, FeishuDocumentMcpProxy feishu, NotificationService notifications, ObjectMapper json) {
        this.jdbc = jdbc; this.feishu = feishu; this.notifications = notifications; this.json = json;
    }

    @Transactional
    public void enqueueForStage(Long stageRunId) {
        List<Map<String,Object>> rows = jdbc.queryForList("select w.project_id,w.id workflow_run_id,s.stage_key,b.project_document_id,b.project_document_revision_id,t.id target_id from stage_run s join workflow_run w on w.id=s.workflow_run_id join workflow_artifact_binding b on b.stage_run_id=s.id and b.relation_type='OUTPUT' join project_feishu_publish_target t on t.project_id=w.project_id and t.status='ACTIVE' where s.id=? order by b.id desc limit 1", stageRunId);
        if (rows.isEmpty()) return;
        Map<String,Object> r=rows.get(0);
        jdbc.update("insert ignore into project_feishu_publication_task(time_created,time_updated,project_id,workflow_run_id,stage_run_id,stage_key,document_id,revision_id,target_id,status,attempt_count,next_attempt_at) values(now(3),now(3),?,?,?,?,?,?,?,'PENDING',0,now(3))",r.get("project_id"),r.get("workflow_run_id"),stageRunId,r.get("stage_key"),r.get("project_document_id"),r.get("project_document_revision_id"),r.get("target_id"));
    }

    @Transactional
    public void enqueueForProject(Long projectId) {
        jdbc.query("select s.id from stage_run s join workflow_run w on w.id=s.workflow_run_id where w.project_id=? and exists(select 1 from workflow_stage_acceptance a where a.stage_run_id=s.id and a.decision='ACCEPT')", (rs,n)->rs.getLong(1), projectId).forEach(this::enqueueForStage);
    }

    @Scheduled(fixedDelayString="${skill-platform.feishu.publication-poll-ms:5000}")
    public void dispatchDue() {
        jdbc.update("update project_feishu_publication_task set status='RETRY_WAIT',next_attempt_at=now(3),time_updated=now(3) where status='RUNNING' and claimed_at < date_sub(now(3), interval 10 minute)");
        jdbc.query("select s.id from stage_run s join workflow_stage_acceptance a on a.stage_run_id=s.id and a.decision='ACCEPT' join workflow_run w on w.id=s.workflow_run_id join project_feishu_publish_target t on t.project_id=w.project_id and t.status='ACTIVE' where not exists(select 1 from project_feishu_publication_task p where p.stage_run_id=s.id)", (rs,n)->rs.getLong(1)).forEach(this::enqueueForStage);
        List<Long> ids=jdbc.query("select p.id from project_feishu_publication_task p where p.status in ('PENDING','RETRY_WAIT') and (p.next_attempt_at is null or p.next_attempt_at<=now(3)) order by p.id limit 10",(rs,n)->rs.getLong(1));
        ids.forEach(this::publish);
    }

    public void publish(Long taskId) {
        int claimed=jdbc.update("update project_feishu_publication_task set status='RUNNING',claimed_at=now(3),attempt_count=attempt_count+1,time_updated=now(3) where id=? and status in ('PENDING','RETRY_WAIT') and (next_attempt_at is null or next_attempt_at<=now(3))",taskId);
        if(claimed==0)return;
        try {
            Map<String,Object> r=jdbc.queryForMap("select t.id,t.project_id,t.stage_key,t.revision_id,t.target_id,t.attempt_count,t.document_token,t.wiki_node_token,t.document_url,p.source_url,p.space_id,p.parent_node_token,p.validated_by,d.title,rv.markdown_content,w.id workflow_run_id,w.project_id,wf.project_key,s.id stage_run_id from project_feishu_publication_task t join project_feishu_publish_target p on p.id=t.target_id join project_document d on d.id=t.document_id join project_document_revision rv on rv.id=t.revision_id left join workflow_run w on w.id=t.workflow_run_id left join virtual_project wf on wf.id=w.project_id left join stage_run s on s.id=t.stage_run_id where t.id=?",taskId);
            Long user=((Number)r.get("validated_by")).longValue(); String nodeToken=(String)r.get("wiki_node_token");
            if(nodeToken==null||nodeToken.isBlank()){
                // Create the docx directly under the wiki node (title preserved), then write content into it.
                JsonNode created=feishu.createWikiDocx(user,String.valueOf(r.get("space_id")),String.valueOf(r.get("parent_node_token")),String.valueOf(r.get("title")));
                String documentToken=created.path("documentToken").asText();
                feishu.writeNativeDocx(user,documentToken,String.valueOf(r.get("markdown_content")));
                String documentUrl=created.path("documentUrl").asText();
                jdbc.update("update project_feishu_publication_task set status='SUCCEEDED',document_token=?,wiki_node_token=?,document_url=?,last_error=null,claimed_at=null,time_updated=now(3) where id=?",documentToken,created.path("nodeToken").asText(),documentUrl,taskId);
                r.put("id",taskId);notifyRecipients(r,true,null,documentUrl);
            }
            else {jdbc.update("update project_feishu_publication_task set status='SUCCEEDED',claimed_at=null,last_error=null,time_updated=now(3) where id=?",taskId);notifyRecipients(r,true,null,(String)r.get("document_url"));}
        } catch(Exception e) {
            log.error("event=feishu.publication.publish_failed taskId={} errorType={} message={}", taskId, e.getClass().getName(), e.getMessage(), e);
            Map<String,Object> current=jdbc.queryForMap("select t.id,t.attempt_count,t.project_id,t.stage_run_id,t.workflow_run_id,p.project_key from project_feishu_publication_task t join virtual_project p on p.id=t.project_id where t.id=?",taskId);
            int attempts=((Number)current.get("attempt_count")).intValue(); boolean exhausted=attempts>=3;
            jdbc.update("update project_feishu_publication_task set status=?,next_attempt_at=?,last_error=?,claimed_at=null,time_updated=now(3) where id=?",exhausted?"FAILED":"RETRY_WAIT",exhausted?null:Instant.now().plusSeconds(attempts==1?60:300),trim(e.getMessage()),taskId);
            if(exhausted) notifyRecipients(current,false,trim(e.getMessage()),null);
        }
    }

    @Transactional
    public PublicationView retry(Long projectId, Long taskId, Long actorId) {
        Integer allowed=jdbc.queryForObject("select count(*) from virtual_project_member where project_id=? and user_id=? and status='ACTIVE' and membership_type='OWNER'",Integer.class,projectId,actorId);
        if(allowed==null||allowed==0)throw error("PROJECT_ACCESS_FORBIDDEN","Project owner required",HttpStatus.FORBIDDEN);
        int n=jdbc.update("update project_feishu_publication_task t join project_feishu_publish_target p on p.id=t.target_id set t.status='PENDING',t.next_attempt_at=now(3),t.last_error=null,t.time_updated=now(3) where t.id=? and p.project_id=? and t.status='FAILED'",taskId,projectId);
        if(n==0)throw error("FEISHU_PUBLICATION_NOT_RETRYABLE","Publication task is not failed",HttpStatus.CONFLICT);
        return task(taskId,projectId);
    }

    @Transactional(readOnly=true) public PublicationView task(Long id,Long projectId){Map<String,Object> r=jdbc.queryForMap("select id,status,attempt_count,last_error,document_url from project_feishu_publication_task where id=? and project_id=?",id,projectId);return new PublicationView(((Number)r.get("id")).longValue(),String.valueOf(r.get("status")),((Number)r.get("attempt_count")).intValue(),(String)r.get("last_error"),(String)r.get("document_url"));}
    @Transactional(readOnly=true)
    public PublicationView latestForStage(Long stageRunId) {
        List<Map<String,Object>> rows=jdbc.queryForList("select id,status,attempt_count,last_error,document_url from project_feishu_publication_task where stage_run_id=? order by id desc limit 1",stageRunId);
        if(rows.isEmpty())return null;
        Map<String,Object> r=rows.get(0);
        return new PublicationView(((Number)r.get("id")).longValue(),String.valueOf(r.get("status")),((Number)r.get("attempt_count")).intValue(),(String)r.get("last_error"),(String)r.get("document_url"));
    }
    private void notifyRecipients(Map<String,Object> r,boolean success,String error,String url){Long project=((Number)r.get("project_id")).longValue();Map<String,Object> data=new LinkedHashMap<>();data.put("projectKey",r.get("project_key"));data.put("runId",r.get("workflow_run_id"));data.put("stageRunId",r.get("stage_run_id"));data.put("publishTaskId",r.get("id"));data.put("documentUrl",url);String payloadValue;try{payloadValue=json.writeValueAsString(data);}catch(Exception e){payloadValue="{}";}final String payload=payloadValue;List<Long> users=jdbc.query("select distinct user_id from virtual_project_member where project_id=? and membership_type='OWNER' and status='ACTIVE'",(rs,n)->rs.getLong(1),project);if(r.get("stage_run_id")!=null)users.addAll(jdbc.query("select accepted_by from workflow_stage_acceptance where stage_run_id=? and accepted_by is not null",(rs,n)->rs.getLong(1),r.get("stage_run_id")));final List<Long> recipients=users;recipients.stream().distinct().forEach(u->notifications.feishuDocumentPublication(u,success?NotificationType.FEISHU_DOCUMENT_PUSH_SUCCEEDED:NotificationType.FEISHU_DOCUMENT_PUSH_FAILED,success?"飞书文档推送成功":"飞书文档推送失败",success?"阶段产物已推送到飞书":"阶段产物推送失败："+error,((Number)r.get("id")).longValue(),payload));}
    private String trim(String s){return s==null?"unknown":s.substring(0,Math.min(1900,s.length()));}
    private BusinessException error(String c,String m,HttpStatus s){return new BusinessException(c,m,s);}
    private record JsonResult(String documentToken,String nodeToken,String url){JsonResult(com.fasterxml.jackson.databind.JsonNode n){this(n.path("documentToken").asText(),n.path("nodeToken").asText(),n.path("documentUrl").asText());}}
    public record PublicationView(Long taskId,String status,int attemptCount,String lastError,String documentUrl){
        public boolean retryable(){return "FAILED".equals(status);}
    }
}
