package com.company.skillplatform.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExternalRoleCardOutbox {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String sources;
    private final int expiryDays;
    private com.company.skillplatform.notification.application.NotificationService notifications;
    @org.springframework.beans.factory.annotation.Autowired public void setNotifications(com.company.skillplatform.notification.application.NotificationService value){notifications=value;}

    public ExternalRoleCardOutbox(JdbcTemplate db,ObjectMapper json,
            @Value("${integration.external.role-card.enabled:false}") boolean enabled,
            @Value("${integration.external.role-card.source-systems:}") String sources,
            @Value("${integration.external.role-card.expiry-days:14}") int expiryDays) {
        this.db=db;this.json=json;this.enabled=enabled;this.sources=sources;this.expiryDays=expiryDays;
    }

    public void capture(ExternalResourceService.PushRequest request,ExternalResourceService.PushResult result) {
        if(!enabled || !allowed(request.sourceSystem())) return;
        Map<String,Object> role=db.queryForMap("SELECT id,role_name,status FROM external_role WHERE source_system=? AND external_role_id=?",request.sourceSystem(),request.role().roleId());
        long roleId=((Number)role.get("id")).longValue();
        List<Map<String,Object>> resources=db.queryForList("SELECT r.id resourceId,r.external_resource_id externalResourceId,r.resource_type resourceType,r.name,r.description,r.development_stage developmentStage,r.sub_stage_key subStageKey,r.current_version_id versionId,v.external_version externalVersion,v.asdm_commands_payload_sha256 commandHash FROM external_role_resource rr JOIN external_resource r ON r.id=rr.resource_id JOIN external_resource_version v ON v.id=r.current_version_id WHERE rr.role_id=? ORDER BY r.id",roleId);
        try {
            Map<String,Object> summary=new LinkedHashMap<>();
            summary.put("created",result.created());summary.put("updated",result.updated());summary.put("unchanged",result.unchanged());summary.put("failed",result.failed());
            List<Map<String,String>> changes=new ArrayList<>();
            for(var success:result.successes()) {
                var input=request.resources().stream().filter(r->r!=null && java.util.Objects.equals(r.resourceId(),success.resourceId())).findFirst().orElse(null);
                Map<String,String> change=new LinkedHashMap<>();
                change.put("status",success.status());change.put("resourceId",success.resourceId());
                if(input!=null){change.put("name",input.resourceName());change.put("type",input.resourceType());change.put("version",input.version());}
                changes.add(change);
            }
            for(var failure:result.failures()) {
                var input=request.resources().stream().filter(r->r!=null && java.util.Objects.equals(r.resourceId(),failure.resourceId())).findFirst().orElse(null);
                Map<String,String> change=new LinkedHashMap<>();
                change.put("status","FAILED");change.put("resourceId",failure.resourceId());change.put("errorCode",failure.errorCode());
                if(input!=null){change.put("name",input.resourceName());change.put("type",input.resourceType());change.put("version",input.version());}
                changes.add(change);
            }
            summary.put("changes",changes);
            String requestId=result.requestId()==null||result.requestId().isBlank()?java.util.UUID.randomUUID().toString():result.requestId();
            if(requestId.length()>128)requestId=requestId.substring(0,128);
            int inserted=db.update("INSERT IGNORE INTO external_role_card_event(role_id,source_system,external_role_id,role_name,role_status,request_id,change_summary_json,resource_snapshot_json,expires_at) VALUES(?,?,?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL ? DAY))",
                    roleId,request.sourceSystem(),request.role().roleId(),role.get("role_name"),role.get("status"),requestId,
                    json.writeValueAsString(summary),
                    json.writeValueAsString(resources),Math.max(1,expiryDays));
            if(inserted>0 && notifications!=null){
                Long eventId=db.queryForObject("SELECT id FROM external_role_card_event WHERE role_id=? AND request_id=?",Long.class,roleId,requestId);
                notifications.externalRoleCardCreated(eventId,String.valueOf(role.get("role_name")));
            }
        } catch(Exception ex) { throw new IllegalStateException("Unable to capture external role card",ex); }
    }

    private boolean allowed(String source) {
        if(sources==null||sources.isBlank())return true;
        return java.util.Arrays.stream(sources.split(",")).map(String::trim).anyMatch(source::equals);
    }
}
