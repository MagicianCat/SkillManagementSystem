package com.company.skillplatform.wiki.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import com.company.skillplatform.wiki.domain.WikiKnowledgeCategory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WikiCategoryBatchService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final WikiCategoryRecommendationService recommendations;
    private final IamUserRoleRepository roles;
    private final NotificationService notifications;
    private final VectorStorePort vectors;
    private final ObjectMapper json;
    private final String workerId = UUID.randomUUID().toString();

    public WikiCategoryBatchService(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager,
            WikiCategoryRecommendationService recommendations, IamUserRoleRepository roles,
            NotificationService notifications, VectorStorePort vectors, ObjectMapper json) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.recommendations = recommendations;
        this.roles = roles; this.notifications = notifications; this.vectors = vectors; this.json = json;
    }

    public void requireAdmin(long actorId) {
        if (!roles.findRoleKeysByUserId(actorId).contains("ADMIN"))
            throw error("WIKI_CATEGORY_ADMIN_REQUIRED", "Super administrator required", HttpStatus.FORBIDDEN);
    }

    public Map<String,Object> preview(long actorId) {
        requireAdmin(actorId);
        int pending = 0, confirmed = 0;
        long cursor = 0;
        while (true) {
            List<Long> ids = candidateIds(cursor, 500);
            if (ids.isEmpty()) break;
            for (Long id : ids) {
                Snapshot snapshot = snapshot(id);
                if (snapshot == null) continue;
                if (confirmedOther(snapshot)) confirmed++; else pending++;
            }
            cursor = ids.get(ids.size()-1);
        }
        Long active = db.query("SELECT active_batch_id FROM wiki_category_batch_guard WHERE id=1",
                rs -> rs.next() ? (Long) rs.getObject(1) : null);
        return Map.of("pendingCount", pending, "confirmedOtherCount", confirmed,
                "estimatedMaxCalls", pending, "activeBatchId", active == null ? 0L : active);
    }

    public Map<String,Object> create(long actorId, boolean includeConfirmedOther) {
        requireAdmin(actorId);
        return tx.execute(status -> {
            Long active = db.query("SELECT active_batch_id FROM wiki_category_batch_guard WHERE id=1 FOR UPDATE",
                    rs -> rs.next() ? (Long) rs.getObject(1) : null);
            if (active != null) {
                List<Map<String,Object>> previous=db.queryForList("SELECT initiated_by,include_confirmed_other,candidate_count,status FROM wiki_category_batch WHERE id=?",active);
                if (!previous.isEmpty() && number(previous.get(0).get("initiated_by"))==actorId &&
                        ((Boolean)previous.get(0).get("include_confirmed_other"))==includeConfirmedOther)
                    return Map.of("batchId",active,"status",previous.get(0).get("status"),"candidateCount",previous.get(0).get("candidate_count"));
                throw error("WIKI_CATEGORY_BATCH_ACTIVE", "A batch is already active: " + active, HttpStatus.CONFLICT);
            }
            KeyHolder key = new GeneratedKeyHolder();
            db.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO wiki_category_batch(status,initiated_by,include_confirmed_other,prompt_version) VALUES('PENDING',?,?,?)", new String[]{"id"});
                statement.setLong(1,actorId); statement.setBoolean(2,includeConfirmedOther);
                statement.setString(3,WikiCategoryRecommendationService.PROMPT_VERSION); return statement;
            }, key);
            long batchId = key.getKey().longValue();
            db.update("UPDATE wiki_category_batch_guard SET active_batch_id=? WHERE id=1", batchId);
            int count = 0;
            long cursor = 0;
            while (true) {
                List<Long> ids = candidateIds(cursor,500);
                if (ids.isEmpty()) break;
                for (Long id : ids) {
                    Snapshot snapshot = snapshot(id);
                    if (snapshot == null || (!includeConfirmedOther && confirmedOther(snapshot))) continue;
                    db.update("INSERT INTO wiki_category_batch_item(batch_id,wiki_document_id,team_id,source_revision_id,source_version_no,content_sha256,input_fingerprint) VALUES(?,?,?,?,?,?,?)",
                            batchId,id,snapshot.teamId(),snapshot.revisionId(),snapshot.version(),snapshot.contentHash(),snapshot.fingerprint());
                    count++;
                }
                cursor = ids.get(ids.size()-1);
            }
            db.update("UPDATE wiki_category_batch SET candidate_count=?,status='RUNNING',started_at=NOW(3) WHERE id=?", count,batchId);
            if (count == 0) finish(batchId);
            return Map.of("batchId",batchId,"status",count == 0 ? "READY_FOR_REVIEW" : "RUNNING","candidateCount",count);
        });
    }

    private List<Long> candidateIds(long cursor, int limit) {
        return db.query("SELECT id FROM wiki_document WHERE id>? AND status='ACTIVE' AND knowledge_category='OTHER' ORDER BY id LIMIT ?",
                (rs,n) -> rs.getLong(1), cursor,limit);
    }

    private Snapshot snapshot(long documentId) {
        List<Map<String,Object>> rows = db.queryForList("SELECT d.id,d.title,d.team_id,d.parent_id,d.version_no,d.current_revision_id,r.markdown_content,r.content_sha256 FROM wiki_document d JOIN wiki_document_revision r ON r.id=d.current_revision_id WHERE d.id=? AND d.status='ACTIVE' AND d.knowledge_category='OTHER'",documentId);
        if (rows.isEmpty()) return null;
        Map<String,Object> row = rows.get(0);
        Long teamId = number(row.get("team_id")); Long parentId = number(row.get("parent_id"));
        List<Long> skills = db.query("SELECT skill_id FROM wiki_document_skill WHERE document_id=? ORDER BY sort_order,skill_id",(rs,n)->rs.getLong(1),documentId);
        var command = new WikiCategoryRecommendationService.SuggestCommand((String)row.get("title"),(String)row.get("markdown_content"),teamId,skills,parentId);
        return new Snapshot(documentId,teamId,number(row.get("current_revision_id")),((Number)row.get("version_no")).intValue(),
                (String)row.get("content_sha256"),recommendations.fingerprint(command),command);
    }

    private boolean confirmedOther(Snapshot snapshot) {
        List<String> values = db.query("SELECT confirmed_input_fingerprint FROM wiki_category_confirmation WHERE wiki_document_id=? AND confirmed_category='OTHER'",(rs,n)->rs.getString(1),snapshot.id());
        return !values.isEmpty() && values.get(0).equals(snapshot.fingerprint());
    }

    @Scheduled(fixedDelayString="${wiki.category.batch.poll-ms:2000}")
    public void process() {
        for (int i=0;i<2;i++) {
            Work work = tx.execute(status -> claim());
            if (work == null) break;
            try {
                Snapshot snapshot = snapshot(work.documentId());
                if (snapshot == null || snapshot.version()!=work.version() || !snapshot.fingerprint().equals(work.fingerprint())) {
                    db.update("UPDATE wiki_category_batch_item SET status='STALE',lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",work.itemId(),workerId);
                } else {
                    List<String> skillNames=db.query("SELECT s.display_name FROM wiki_document_skill l JOIN skill s ON s.id=l.skill_id WHERE l.document_id=? ORDER BY l.sort_order,l.skill_id",(rs,n)->rs.getString(1),work.documentId());
                    var suggestion = recommendations.suggestTrusted(snapshot.command(),skillNames);
                    db.update("UPDATE wiki_category_batch_item SET status='SUGGESTED',suggested_category=?,selected_probability=?,probabilities_json=?,model_key=?,prompt_version=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",
                            suggestion.recommendedCategory(),suggestion.confidence(),toJson(suggestion.probabilities()),suggestion.model(),suggestion.promptVersion(),work.itemId(),workerId);
                }
            } catch (Exception failure) {
                String code = failure instanceof BusinessException business ? business.getCode() : "WIKI_CATEGORY_BATCH_ERROR";
                db.update("UPDATE wiki_category_batch_item SET status=IF(attempt_count<3,'PENDING','FAILED'),error_code=?,next_attempt_at=IF(attempt_count<3,DATE_ADD(NOW(3),INTERVAL POW(2,attempt_count) SECOND),NULL),lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",code,work.itemId(),workerId);
            }
            tx.executeWithoutResult(status -> finishIfDone(work.batchId()));
        }
    }

    private Work claim() {
        List<Map<String,Object>> rows = db.queryForList("SELECT i.id,i.batch_id,i.wiki_document_id,i.source_version_no,i.input_fingerprint,b.initiated_by FROM wiki_category_batch_item i JOIN wiki_category_batch b ON b.id=i.batch_id WHERE b.status='RUNNING' AND ((i.status='PENDING' AND (i.next_attempt_at IS NULL OR i.next_attempt_at<=NOW(3))) OR (i.status='PROCESSING' AND i.lease_until<NOW(3))) ORDER BY i.id LIMIT 1");
        if (rows.isEmpty()) return null;
        Map<String,Object> row=rows.get(0); long id=number(row.get("id"));
        int claimed=db.update("UPDATE wiki_category_batch_item SET status='PROCESSING',lease_owner=?,lease_until=DATE_ADD(NOW(3),INTERVAL 5 MINUTE),attempt_count=attempt_count+1 WHERE id=? AND ((status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=NOW(3))) OR (status='PROCESSING' AND lease_until<NOW(3)))",workerId,id);
        if (claimed==0) return null;
        return new Work(id,number(row.get("batch_id")),number(row.get("wiki_document_id")),((Number)row.get("source_version_no")).intValue(),(String)row.get("input_fingerprint"),number(row.get("initiated_by")));
    }

    private void finishIfDone(long batchId) {
        int pending=db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=? AND status IN ('PENDING','PROCESSING')",Integer.class,batchId);
        if (pending>0) return;
        finish(batchId);
    }

    private void finish(long batchId) {
        int success=db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=? AND status IN ('SUGGESTED','CONFIRMED_AI','MANUAL_CORRECTED','CONFIRMED_OTHER')",Integer.class,batchId); int failed=count(batchId,"FAILED"); int stale=count(batchId,"STALE");
        String result=failed>0 ? "PARTIAL_FAILED" : "READY_FOR_REVIEW";
        db.update("UPDATE wiki_category_batch SET status=?,succeeded_count=?,failed_count=?,skipped_count=?,finished_at=NOW(3) WHERE id=? AND status='RUNNING'",result,success,failed,stale,batchId);
        db.update("UPDATE wiki_category_batch_guard SET active_batch_id=NULL WHERE id=1 AND active_batch_id=?",batchId);
        int notified=db.update("UPDATE wiki_category_batch SET notification_sent_at=NOW(3) WHERE id=? AND notification_sent_at IS NULL",batchId);
        if (notified>0) {
            Long actor=db.queryForObject("SELECT initiated_by FROM wiki_category_batch WHERE id=?",Long.class,batchId);
            notifications.wikiCategoryBatchReady(actor,batchId,success,failed,stale);
        }
    }

    private int count(long batchId,String status) { return db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=? AND status=?",Integer.class,batchId,status); }
    public Map<String,Object> batch(long actorId,long batchId) {
        requireAdmin(actorId);
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM wiki_category_batch WHERE id=?",batchId);
        if(rows.isEmpty()) throw error("WIKI_CATEGORY_BATCH_NOT_FOUND","Batch not found",HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
    public List<Map<String,Object>> batches(long actorId) { requireAdmin(actorId); return db.queryForList("SELECT * FROM wiki_category_batch ORDER BY id DESC LIMIT 100"); }
    public Map<String,Object> items(long actorId,long batchId,String status,int page,int size) {
        batch(actorId,batchId);
        int safeSize=Math.min(50,Math.max(1,size)); int safePage=Math.max(0,page);
        String filter=status==null||status.isBlank() ? "" : " AND i.status=?";
        Object[] args=status==null||status.isBlank() ? new Object[]{batchId,safeSize,safePage*safeSize} : new Object[]{batchId,status,safeSize,safePage*safeSize};
        List<Map<String,Object>> rows=db.queryForList("SELECT i.*,d.title,d.knowledge_category AS current_category,p.approved_knowledge_category AS platform_category FROM wiki_category_batch_item i JOIN wiki_document d ON d.id=i.wiki_document_id LEFT JOIN wiki_platform_snapshot p ON p.document_id=d.id WHERE i.batch_id=?"+filter+" ORDER BY i.id LIMIT ? OFFSET ?",args);
        int total=status==null||status.isBlank() ? db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=?",Integer.class,batchId)
                : db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=? AND status=?",Integer.class,batchId,status);
        return Map.of("items",rows,"totalElements",total,"page",safePage,"size",safeSize,"totalPages",(total+safeSize-1)/safeSize);
    }

    public Map<String,Object> retry(long actorId,long batchId) {
        requireAdmin(actorId);
        return tx.execute(status->{
            db.queryForObject("SELECT id FROM wiki_category_batch_guard WHERE id=1 FOR UPDATE",Long.class);
            Long active=db.query("SELECT active_batch_id FROM wiki_category_batch_guard WHERE id=1",rs->rs.next()?(Long)rs.getObject(1):null);
            if(active!=null) throw error("WIKI_CATEGORY_BATCH_ACTIVE","Another batch is running",HttpStatus.CONFLICT);
            int changed=0;
            List<Map<String,Object>> old=db.queryForList("SELECT id,wiki_document_id,status FROM wiki_category_batch_item WHERE batch_id=? AND status IN ('FAILED','STALE')",batchId);
            for(Map<String,Object> row:old) {
                Snapshot current=snapshot(number(row.get("wiki_document_id")));
                if(current==null) continue;
                changed+=db.update("UPDATE wiki_category_batch_item SET status='PENDING',attempt_count=0,error_code=NULL,next_attempt_at=NULL,source_revision_id=?,source_version_no=?,content_sha256=?,input_fingerprint=? WHERE id=?",
                        current.revisionId(),current.version(),current.contentHash(),current.fingerprint(),number(row.get("id")));
            }
            if(changed>0){db.update("UPDATE wiki_category_batch SET status='RUNNING',notification_sent_at=NULL,finished_at=NULL WHERE id=? AND status IN ('PARTIAL_FAILED','READY_FOR_REVIEW')",batchId);db.update("UPDATE wiki_category_batch_guard SET active_batch_id=? WHERE id=1",batchId);}
            return Map.of("retriedCount",changed);
        });
    }
    public void cancel(long actorId,long batchId) { requireAdmin(actorId); tx.executeWithoutResult(status->{db.update("UPDATE wiki_category_batch SET status='CANCELLED',finished_at=NOW(3) WHERE id=? AND status='RUNNING'",batchId);db.update("UPDATE wiki_category_batch_guard SET active_batch_id=NULL WHERE id=1 AND active_batch_id=?",batchId);}); }

    public Map<String,Object> review(long actorId,long itemId,int expectedVersion,String fingerprint,String action,String finalCategory) {
        requireAdmin(actorId);
        WikiKnowledgeCategory category;
        try { category=WikiKnowledgeCategory.valueOf(finalCategory); } catch(Exception failure){throw error("WIKI_CATEGORY_INVALID","Invalid category",HttpStatus.BAD_REQUEST);}
        if(!List.of("ACCEPT","OVERRIDE","KEEP_OTHER").contains(action)) throw error("WIKI_CATEGORY_REVIEW_ACTION_INVALID","Invalid action",HttpStatus.BAD_REQUEST);
        Map<String,Object> result = tx.execute(status->{
            List<Map<String,Object>> rows=db.queryForList("SELECT * FROM wiki_category_batch_item WHERE id=? FOR UPDATE",itemId);
            if(rows.isEmpty()) throw error("WIKI_CATEGORY_ITEM_NOT_FOUND","Item not found",HttpStatus.NOT_FOUND);
            Map<String,Object> item=rows.get(0);
            if(!"SUGGESTED".equals(item.get("status"))) throw error("WIKI_CATEGORY_ITEM_NOT_REVIEWABLE","Item already reviewed or not ready",HttpStatus.CONFLICT);
            if(!fingerprint.equals(item.get("input_fingerprint"))) throw error("WIKI_CATEGORY_FINGERPRINT_CONFLICT","Fingerprint mismatch",HttpStatus.CONFLICT);
            if("ACCEPT".equals(action) && !category.name().equals(item.get("suggested_category"))) throw error("WIKI_CATEGORY_REVIEW_ACTION_INVALID","Accept requires suggested category",HttpStatus.BAD_REQUEST);
            if("KEEP_OTHER".equals(action) && category!=WikiKnowledgeCategory.OTHER) throw error("WIKI_CATEGORY_REVIEW_ACTION_INVALID","Keep other requires OTHER",HttpStatus.BAD_REQUEST);
            long documentId=number(item.get("wiki_document_id"));
            Snapshot snapshot=snapshot(documentId);
            if(snapshot==null || snapshot.version()!=expectedVersion || snapshot.version()!=((Number)item.get("source_version_no")).intValue() || !snapshot.fingerprint().equals(fingerprint)) {
                db.update("UPDATE wiki_category_batch_item SET status='STALE' WHERE id=?",itemId);
                return Map.of("itemId",itemId,"status","STALE");
            }
            Integer locked=db.queryForObject("SELECT COUNT(*) FROM wiki_active_review_lock WHERE document_id=?",Integer.class,documentId);
            if(locked!=null && locked>0) throw error("WIKI_CATEGORY_REVIEW_LOCKED","Wiki promotion review is active",HttpStatus.CONFLICT);
            int updated=db.update("UPDATE wiki_document SET knowledge_category=?,updated_by=?,version_no=version_no+1 WHERE id=? AND version_no=? AND status='ACTIVE' AND knowledge_category='OTHER'",category.name(),actorId,documentId,expectedVersion);
            if(updated!=1) throw error("WIKI_CATEGORY_STALE","Wiki has changed",HttpStatus.CONFLICT);
            if(snapshot.teamId()==null) db.update("UPDATE wiki_platform_snapshot SET approved_knowledge_category=? WHERE document_id=?",category.name(),documentId);
            db.update("INSERT INTO wiki_category_confirmation(wiki_document_id,confirmed_category,confirmed_input_fingerprint,confirmed_revision_id,confirmed_by,confirmation_source) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE confirmed_category=VALUES(confirmed_category),confirmed_input_fingerprint=VALUES(confirmed_input_fingerprint),confirmed_revision_id=VALUES(confirmed_revision_id),confirmed_by=VALUES(confirmed_by),confirmation_source=VALUES(confirmation_source),confirmed_at=NOW(3)",documentId,category.name(),fingerprint,snapshot.revisionId(),actorId,category==WikiKnowledgeCategory.OTHER?"ADMIN_CONFIRMED_OTHER":"ACCEPT".equals(action)?"AI_ACCEPTED":"AI_CORRECTED");
            db.update("UPDATE wiki_category_batch_item SET status=?,review_category=?,review_action=?,reviewer_id=?,reviewed_at=NOW(3),applied_at=NOW(3),index_status='PENDING' WHERE id=?",
                    category==WikiKnowledgeCategory.OTHER?"CONFIRMED_OTHER":"ACCEPT".equals(action)?"CONFIRMED_AI":"MANUAL_CORRECTED",category.name(),action,actorId,itemId);
            long batchId=number(item.get("batch_id"));
            db.update("UPDATE wiki_category_batch SET reviewed_count=reviewed_count+1 WHERE id=?",batchId);
            int remaining=db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=? AND status IN ('SUGGESTED','PENDING','PROCESSING','FAILED','STALE')",Integer.class,batchId);
            if(remaining==0) db.update("UPDATE wiki_category_batch SET status='REVIEW_COMPLETED' WHERE id=? AND status='READY_FOR_REVIEW'",batchId);
            return Map.of("itemId",itemId,"status","CONFIRMED","finalCategory",category.name());
        });
        if ("STALE".equals(result.get("status"))) throw error("WIKI_CATEGORY_STALE","Wiki has changed; rescan required",HttpStatus.CONFLICT);
        return result;
    }

    public List<Map<String,Object>> reviewMany(long actorId,long batchId,List<ReviewCommand> commands) {
        requireAdmin(actorId);
        if(commands==null || commands.isEmpty() || commands.size()>100) throw error("WIKI_CATEGORY_BATCH_REVIEW_INVALID","Choose 1..100 items",HttpStatus.BAD_REQUEST);
        List<Map<String,Object>> results=new ArrayList<>();
        for(ReviewCommand command:commands) {
            if (db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE id=? AND batch_id=?",Integer.class,command.itemId(),batchId)==0) {
                results.add(Map.of("itemId",command.itemId(),"status","FAILED","code","WIKI_CATEGORY_ITEM_NOT_IN_BATCH")); continue;
            }
            try { results.add(review(actorId,command.itemId(),command.expectedWikiVersionNo(),command.inputFingerprint(),command.reviewAction(),command.finalCategory())); }
            catch(BusinessException failure){results.add(Map.of("itemId",command.itemId(),"status","FAILED","code",failure.getCode()));}
        }
        return results;
    }

    @Scheduled(fixedDelayString="${wiki.category.batch.index-poll-ms:10000}")
    public void refreshIndex() {
        List<Map<String,Object>> rows=db.queryForList("SELECT id,wiki_document_id,team_id,review_category FROM wiki_category_batch_item WHERE index_status IN ('PENDING','FAILED') ORDER BY id LIMIT 20");
        for(Map<String,Object> row:rows) try {
            long id=number(row.get("id"));long doc=number(row.get("wiki_document_id"));String category=(String)row.get("review_category");
            List<String> current=db.query("SELECT knowledge_category FROM wiki_document WHERE id=? AND status='ACTIVE'",(rs,n)->rs.getString(1),doc);
            if(current.isEmpty() || !category.equals(current.get(0))) { db.update("UPDATE wiki_category_batch_item SET index_status='DONE' WHERE id=?",id); continue; }
            vectors.setKnowledgeCategory(doc,row.get("team_id")==null?"PLATFORM":"TEAM",category);
            db.update("UPDATE wiki_category_batch_item SET index_status='DONE' WHERE id=?",id);
        } catch(Exception failure) {
            db.update("UPDATE wiki_category_batch_item SET index_status='FAILED' WHERE id=?",number(row.get("id")));
        }
    }

    public Map<String,Object> metrics(long actorId,long batchId) {
        batch(actorId,batchId);
        int reviewed=db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=? AND reviewed_at IS NOT NULL AND (model_key IS NULL OR model_key<>'rule:asdm')",Integer.class,batchId);
        int matched=db.queryForObject("SELECT COUNT(*) FROM wiki_category_batch_item WHERE batch_id=? AND reviewed_at IS NOT NULL AND (model_key IS NULL OR model_key<>'rule:asdm') AND suggested_category=review_category",Integer.class,batchId);
        List<Map<String,Object>> confusion=db.queryForList("SELECT suggested_category,review_category,COUNT(*) AS sample_count FROM wiki_category_batch_item WHERE batch_id=? AND reviewed_at IS NOT NULL AND (model_key IS NULL OR model_key<>'rule:asdm') GROUP BY suggested_category,review_category",batchId);
        return Map.of("reviewed",reviewed,"matched",matched,"accuracy",reviewed==0?0.0:(double)matched/reviewed,"confusion",confusion);
    }

    private String toJson(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException failure){throw new IllegalStateException(failure);} }
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static BusinessException error(String code,String message,HttpStatus status){return new BusinessException(code,message,status);}
    private record Snapshot(long id,Long teamId,long revisionId,int version,String contentHash,String fingerprint,WikiCategoryRecommendationService.SuggestCommand command){}
    private record Work(long itemId,long batchId,long documentId,int version,String fingerprint,long actorId){}
    public record ReviewCommand(long itemId,int expectedWikiVersionNo,String inputFingerprint,String reviewAction,String finalCategory){}
}
