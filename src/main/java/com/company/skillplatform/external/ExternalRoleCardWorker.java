package com.company.skillplatform.external;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.collabproject.application.CollabFeishuChatGateway;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ExternalRoleCardWorker {
    private static final Logger log=LoggerFactory.getLogger(ExternalRoleCardWorker.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ExternalRoleCardFactory factory;
    private final CollabFeishuChatGateway chats;
    private final IamUserRoleRepository roles;
    private final FeishuProperties feishu;
    private final boolean enabled;

    public ExternalRoleCardWorker(JdbcTemplate db,ObjectMapper json,ExternalRoleCardFactory factory,CollabFeishuChatGateway chats,
            IamUserRoleRepository roles,FeishuProperties feishu,
            @Value("${integration.external.role-card.enabled:false}") boolean enabled) {
        this.db=db;this.json=json;this.factory=factory;this.chats=chats;this.roles=roles;this.feishu=feishu;this.enabled=enabled;
    }

    @Scheduled(fixedDelayString="${integration.external.role-card.poll-ms:5000}")
    public void poll() {
        reconcileActions();
        db.update("UPDATE external_role_card_event SET status='EXPIRED',next_attempt_at=NULL WHERE expires_at<=NOW(3) AND status IN ('PENDING','PARTIAL','FAILED','SENT')");
        if(!enabled)return;
        if(!feishu.eventEnabled()) {log.warn("event=external.role.card.paused reason=feishu_long_connection_disabled");return;}
        List<Long> ids=db.query("SELECT id FROM external_role_card_event WHERE (status='PENDING' OR (status IN ('PARTIAL','FAILED') AND next_attempt_at IS NOT NULL AND next_attempt_at<=NOW(3))) AND expires_at>NOW(3) ORDER BY id LIMIT 1",(rs,n)->rs.getLong(1));
        if(ids.isEmpty())return;
        long eventId=ids.get(0);
        if(db.update("UPDATE external_role_card_event SET status='SENDING',attempt_count=attempt_count+1 WHERE id=? AND status IN ('PENDING','PARTIAL','FAILED')",eventId)!=1)return;
        try {
            Map<String,Object> event=db.queryForMap("SELECT * FROM external_role_card_event WHERE id=?",eventId);
            Map<String,Object> snapshot=factory.prepare(eventId,event.get("resource_snapshot_json"));
            db.update("UPDATE external_role_card_event SET resource_snapshot_json=? WHERE id=?",json.writeValueAsString(snapshot),eventId);
            Object card=factory.card(event,snapshot);
            for(IamUserEntity admin:roles.findActiveSuperAdmins())
                db.update("INSERT IGNORE INTO external_role_card_delivery(event_id,recipient_user_id) VALUES(?,?)",eventId,admin.getId());
            List<Map<String,Object>> deliveries=db.queryForList("SELECT d.id,d.recipient_user_id,u.feishu_open_id,u.identity_provider,u.external_user_id,u.username FROM external_role_card_delivery d JOIN iam_user u ON u.id=d.recipient_user_id WHERE d.event_id=? AND d.status IN ('PENDING','RETRY_WAIT') AND (d.next_attempt_at IS NULL OR d.next_attempt_at<=NOW(3))",eventId);
            for(var delivery:deliveries)send(eventId,delivery,card);
            int sent=db.queryForObject("SELECT COUNT(*) FROM external_role_card_delivery WHERE event_id=? AND status='SENT'",Integer.class,eventId);
            int retry=db.queryForObject("SELECT COUNT(*) FROM external_role_card_delivery WHERE event_id=? AND status='RETRY_WAIT'",Integer.class,eventId);
            int failed=db.queryForObject("SELECT COUNT(*) FROM external_role_card_delivery WHERE event_id=? AND status='FAILED'",Integer.class,eventId);
            int skipped=db.queryForObject("SELECT COUNT(*) FROM external_role_card_delivery WHERE event_id=? AND status='SKIPPED_NO_OPEN_ID'",Integer.class,eventId);
            if(retry>0) db.update("UPDATE external_role_card_event SET status='PARTIAL',next_attempt_at=(SELECT MIN(next_attempt_at) FROM external_role_card_delivery WHERE event_id=? AND status='RETRY_WAIT') WHERE id=?",eventId,eventId);
            else db.update("UPDATE external_role_card_event SET status=?,sent_at=CASE WHEN ?>0 THEN NOW(3) ELSE sent_at END,next_attempt_at=NULL WHERE id=?",sent>0&&failed==0&&skipped==0?"SENT":sent>0?"PARTIAL":"FAILED",sent,eventId);
        } catch(Exception ex) {
            log.error("event=external.role.card.failed cardEventId={}",eventId,ex);
            db.update("UPDATE external_role_card_event SET status='FAILED',last_error=?,next_attempt_at=DATE_ADD(NOW(3),INTERVAL 5 MINUTE) WHERE id=?",safe(ex),eventId);
        }
    }

    private void reconcileActions() {
        db.update("UPDATE external_role_card_action a JOIN external_import_job j ON j.id=a.job_id SET a.status=CASE WHEN j.status='FAILED' THEN 'FAILED' WHEN j.failure_count>0 AND j.success_count>0 THEN 'PARTIAL' WHEN j.failure_count>0 THEN 'FAILED' ELSE 'SUCCEEDED' END,a.finished_at=NOW(3) WHERE a.status IN ('ACCEPTED','RUNNING') AND j.status IN ('COMPLETED','FAILED')");
    }

    private void send(long eventId,Map<String,Object> delivery,Object card) {
        long id=((Number)delivery.get("id")).longValue();long userId=((Number)delivery.get("recipient_user_id")).longValue();
        String openId=openId(delivery);
        if(openId==null){db.update("UPDATE external_role_card_delivery SET status='SKIPPED_NO_OPEN_ID',last_error='Missing Feishu Open ID' WHERE id=?",id);return;}
        try {
            String stable=UUID.nameUUIDFromBytes(("external-role-card:"+eventId+":"+userId).getBytes(StandardCharsets.UTF_8)).toString();
            String messageId=chats.sendInteractiveToUser(openId,card,stable);
            db.update("UPDATE external_role_card_delivery SET status='SENT',attempt_count=attempt_count+1,sent_at=NOW(3),feishu_message_id=?,last_error=NULL WHERE id=?",messageId,id);
        } catch(RuntimeException ex) {
            int count=db.queryForObject("SELECT attempt_count FROM external_role_card_delivery WHERE id=?",Integer.class,id)+1;
            int minutes=count<=1?1:count==2?2:count==3?5:15;
            db.update("UPDATE external_role_card_delivery SET status=?,attempt_count=?,last_error=?,next_attempt_at=DATE_ADD(NOW(3),INTERVAL ? MINUTE) WHERE id=?",count>=8?"FAILED":"RETRY_WAIT",count,safe(ex),minutes,id);
        }
    }

    private String openId(Map<String,Object> user) {
        String direct=text(user.get("feishu_open_id"));if(valid(direct))return direct;
        if(!"FEISHU".equals(text(user.get("identity_provider"))))return null;
        String external=text(user.get("external_user_id"));if(valid(external))return external;
        String username=text(user.get("username"));return username!=null&&username.startsWith("feishu_")&&valid(username.substring(7))?username.substring(7):null;
    }
    private boolean valid(String value){return value!=null&&value.matches("ou_[A-Za-z0-9_-]{8,128}");}
    private String text(Object value){return value==null?null:String.valueOf(value);}
    private String safe(Exception ex){String message=ex.getMessage();return message==null?ex.getClass().getSimpleName():message.substring(0,Math.min(2048,message.length()));}
}
