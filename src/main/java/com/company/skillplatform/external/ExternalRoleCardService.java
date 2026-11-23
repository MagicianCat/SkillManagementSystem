package com.company.skillplatform.external;

import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExternalRoleCardService {
    private static final Logger log=LoggerFactory.getLogger(ExternalRoleCardService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ExternalBulkImportService bulk;
    private final ExternalResourceAdminService imports;
    private final TransactionTemplate transactions;
    private ExternalRoleWikiParentService roleWikiParents;
    @org.springframework.beans.factory.annotation.Autowired public void setRoleWikiParents(ExternalRoleWikiParentService value) { this.roleWikiParents=value; }

    public ExternalRoleCardService(JdbcTemplate db,ObjectMapper json,ExternalBulkImportService bulk,
            ExternalResourceAdminService imports,TransactionTemplate transactions) {
        this.db=db;this.json=json;this.bulk=bulk;this.imports=imports;this.transactions=transactions;
    }

    public boolean handles(P2CardActionTrigger event) {
        var action=event==null||event.getEvent()==null?null:event.getEvent().getAction();
        return action!=null && (action.getName()!=null&&action.getName().startsWith("external_role_") ||
                action.getValue()!=null && action.getValue().containsKey("eventId") &&
                Set.of("SKILL_PUBLISH_ALL","WIKI_IMPORT","ASDM_PUBLISH").contains(String.valueOf(action.getValue().get("action"))));
    }

    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
        P2CardActionTriggerResponse response=new P2CardActionTriggerResponse();CallBackToast toast=new CallBackToast();response.setToast(toast);
        try {
            var callback=event.getEvent();var operator=callback.getOperator();
            if(operator==null)throw bad("无法识别飞书操作者");
            String open=operator.getOpenId(),user=operator.getUserId();
            List<Long> actors=db.query("SELECT id FROM iam_user WHERE status='ACTIVE' AND ((? IS NOT NULL AND feishu_open_id=?) OR (? IS NOT NULL AND feishu_user_id=?)) LIMIT 2",(rs,n)->rs.getLong(1),open,open,user,user);
            if(actors.size()!=1)throw bad("飞书账号尚未关联唯一的研枢用户");
            Map<String,Object> value=callback.getAction().getValue();
            if(value==null)throw bad("卡片操作无效");
            long eventId=number(value.get("eventId"));String action=String.valueOf(value.get("action"));
            long version="ASDM_PUBLISH".equals(action)?number(value.get("versionId")):0;
            long job=process(eventId,actors.get(0),action,version,callback.getAction().getFormValue());
            toast.setType("success");toast.setContent(switch(action){case "SKILL_PUBLISH_ALL"->"已受理，正在将全部 Skill 直接发布到全平台";case "WIKI_IMPORT"->"已受理，正在导入所选 Wiki";default->"已受理，正在推送 ASDM 工具包";});
            log.info("event=external.role.card.action_accepted cardEventId={} jobId={} operatorId={}",eventId,job,actors.get(0));
        } catch(DuplicateKeyException duplicate){toast.setType("error");toast.setContent("该操作已由其他超级管理员提交，请查看最新任务结果");}
        catch(BusinessException failure){toast.setType("error");toast.setContent(failure.getMessage());}
        catch(Exception failure){log.warn("event=external.role.card.callback_failed",failure);toast.setType("error");toast.setContent("卡片处理失败，请稍后重试");}
        return response;
    }

    long process(long eventId,long actor,String action,long version,Map<String,Object> form) {
        if(!Set.of("SKILL_PUBLISH_ALL","WIKI_IMPORT","ASDM_PUBLISH").contains(action))throw bad("卡片操作无效");
        Integer admin=db.queryForObject("SELECT COUNT(*) FROM iam_user u JOIN iam_user_role ur ON ur.user_id=u.id JOIN iam_role r ON r.id=ur.role_id WHERE u.id=? AND u.status='ACTIVE' AND r.role_key='ADMIN' AND r.status='ACTIVE'",Integer.class,actor);
        if(admin==null||admin==0)throw bad("只有超级管理员可以处理此卡片");
        Map<String,Object> event=db.query("SELECT * FROM external_role_card_event WHERE id=?",rs->rs.next()?Map.ofEntries(
                Map.entry("role_id",rs.getLong("role_id")),Map.entry("status",rs.getString("status")),Map.entry("resource_snapshot_json",rs.getString("resource_snapshot_json")),Map.entry("expires_at",rs.getTimestamp("expires_at"))):null,eventId);
        if(event==null)throw bad("卡片事件不存在");
        if(((java.sql.Timestamp)event.get("expires_at")).before(new java.sql.Timestamp(System.currentTimeMillis())))throw bad("卡片已过期，请打开后台");
        if(Set.of("CANCELLED","EXPIRED").contains(event.get("status")))throw bad("卡片已失效");
        long roleId=number(event.get("role_id"));
        Integer active=db.queryForObject("SELECT COUNT(*) FROM external_role WHERE id=? AND status='ACTIVE'",Integer.class,roleId);
        if(active==null||active==0)throw bad("角色已停用，无法处理旧卡片");
        Map<String,Object> snapshot;
        try{snapshot=json.readValue(String.valueOf(event.get("resource_snapshot_json")),new TypeReference<>(){});}
        catch(Exception invalid){throw bad("卡片快照无效");}
        @SuppressWarnings("unchecked") List<Map<String,Object>> resources=(List<Map<String,Object>>)snapshot.get("resources");
        if(resources==null)throw bad("卡片快照无效");
        String key;List<Long> skillIds=null;List<ExternalBulkImportService.WikiSelection> selections=null;
        Long teamId=null,parentId=null;String category=null;boolean link=false;long asdmResource=0;
        if("SKILL_PUBLISH_ALL".equals(action)) {
            Integer permission=db.queryForObject("SELECT COUNT(*) FROM iam_user_role ur JOIN iam_role r ON r.id=ur.role_id JOIN iam_role_permission rp ON rp.role_id=r.id JOIN iam_permission p ON p.id=rp.permission_id WHERE ur.user_id=? AND r.role_key='ADMIN' AND r.status='ACTIVE' AND p.permission_key='external:resource:skill:publish:direct'",Integer.class,actor);
            if(permission==null||permission==0)throw bad("缺少外部 Skill 直发权限");
            List<Map<String,Object>> skills=resources.stream().filter(r->"SKILL".equals(r.get("resourceType"))).toList();
            if(skills.isEmpty())throw bad("卡片中没有 Skill");
            skills.forEach(r->validateVersion(roleId,r));skillIds=skills.stream().map(r->number(r.get("resourceId"))).toList();key="SKILL_ALL";
        } else if("ASDM_PUBLISH".equals(action)) {
            Map<String,Object> selected=resources.stream().filter(r->"ASDM-TOOL".equals(r.get("resourceType"))&&number(r.get("versionId"))==version).findFirst().orElseThrow(()->bad("ASDM 资源不在卡片快照中"));
            validateVersion(roleId,selected);asdmResource=number(selected.get("resourceId"));key="ASDM:"+version;
        } else {
            if(form==null)throw bad("请填写 Wiki 导入表单");
            @SuppressWarnings("unchecked") Map<String,Map<String,Object>> docs=(Map<String,Map<String,Object>>)snapshot.get("docs");
            if(docs==null||Boolean.TRUE.equals(snapshot.get("docsOverflow")))throw bad("Wiki 选项过多，请打开后台选择");
            Object chosen=form.get("docs");
            if(!(chosen instanceof List<?> values)||values.isEmpty())throw bad("请至少选择一篇 Markdown");
            Set<String> unique=new HashSet<>();Map<Long,List<String>> byResource=new LinkedHashMap<>();
            for(Object option:values){String id=String.valueOf(option);if(!unique.add(id)||!docs.containsKey(id))throw bad("Wiki 选项无效");
                var doc=docs.get(id);long resourceId=number(doc.get("resourceId"));long selectedVersion=number(doc.get("versionId"));
                Map<String,Object> resource=resources.stream().filter(r->number(r.get("resourceId"))==resourceId&&number(r.get("versionId"))==selectedVersion).findFirst().orElseThrow(()->bad("资源已更新，请使用最新卡片"));
                validateVersion(roleId,resource);byResource.computeIfAbsent(resourceId,ignored->new ArrayList<>()).add(String.valueOf(doc.get("path")));
            }
            selections=byResource.entrySet().stream().map(e->new ExternalBulkImportService.WikiSelection(e.getKey(),e.getValue())).toList();
            String scope=String.valueOf(form.getOrDefault("scope","platform"));
            if(scope.startsWith("team:")){teamId=number(scope.substring(5));Integer valid=db.queryForObject("SELECT COUNT(*) FROM org_team WHERE id=? AND status='ACTIVE'",Integer.class,teamId);if(valid==null||valid==0)throw bad("目标团队已失效");}
            else if(!"platform".equals(scope))throw bad("Wiki 发布范围无效");
            category=ExternalBulkImportService.requiredWikiCategory(String.valueOf(form.get("category")));
            String parent=String.valueOf(form.getOrDefault("parent","root"));
            if(parent.startsWith("parent:")){parentId=number(parent.substring(7));roleWikiParents.requireEligible(roleId,teamId,parentId);}
            else if(!"root".equals(parent))throw bad("父 Wiki 选项无效");
            link="true".equals(String.valueOf(form.getOrDefault("linkSkills","false")));
            if(link&&!imports.readiness(roleId).ready())throw bad("请先完成全部 Skill 全平台发布，再关联 Wiki");
            key="WIKI_IMPORT";
        }
        final String actionKey=key;final List<Long> finalSkills=skillIds;final List<ExternalBulkImportService.WikiSelection> finalSelections=selections;
        final Long finalTeam=teamId,finalParent=parentId;final String finalCategory=category;final boolean finalLink=link;final long finalAsdm=asdmResource;
        try { return transactions.execute(tx->{
            try {
                String payload=json.writeValueAsString(Map.of("action",action,"eventId",eventId,"versionId",version,"form",form==null?Map.of():form));
                db.update("INSERT INTO external_role_card_action(event_id,action_key,operator_id,request_payload_json) VALUES(?,?,?,?)",eventId,actionKey,actor,payload);
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
            long job="SKILL_PUBLISH_ALL".equals(action)?bulk.enqueueSkillsDirect(roleId,finalSkills,actor):
                    "ASDM_PUBLISH".equals(action)?bulk.enqueueAsdm(roleId,finalAsdm,version,actor):
                    bulk.enqueueWikis(roleId,finalTeam,finalParent,finalLink,finalCategory,finalSelections,actor);
            db.update("UPDATE external_role_card_action SET job_id=? WHERE event_id=? AND action_key=?",job,eventId,actionKey);
            db.update("INSERT INTO audit_log(time_created,time_updated,event_type,actor_user_id,target_type,target_id,request_id,result,metadata_json) VALUES(NOW(3),NOW(3),'EXTERNAL_ROLE_CARD_ACTION_ACCEPTED',?,'EXTERNAL_ROLE_CARD',?,?,'SUCCESS',JSON_OBJECT('jobId',?,'action',?))",actor,eventId,"card-"+eventId,job,action);
            return job;
        }); } catch(DuplicateKeyException duplicate){throw duplicate;}
    }

    private void validateVersion(long roleId,Map<String,Object> resource) {
        Integer valid=db.queryForObject("SELECT COUNT(*) FROM external_role_resource rr JOIN external_resource r ON r.id=rr.resource_id JOIN external_resource_version v ON v.id=r.current_version_id WHERE rr.role_id=? AND r.id=? AND r.current_version_id=? AND r.name=? AND r.description <=> ? AND r.development_stage <=> ? AND r.sub_stage_key <=> ? AND v.asdm_commands_payload_sha256 <=> ?",Integer.class,
                roleId,number(resource.get("resourceId")),number(resource.get("versionId")),resource.get("name"),resource.get("description"),resource.get("developmentStage"),resource.get("subStageKey"),resource.get("commandHash"));
        if(valid==null||valid==0)throw bad("资源已更新，请使用最新卡片");
    }
    private long number(Object value){try{return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value));}catch(Exception ex){throw bad("卡片参数无效");}}
    private BusinessException bad(String message){return new BusinessException("EXTERNAL_ROLE_CARD_INVALID",message,HttpStatus.BAD_REQUEST);}
}
