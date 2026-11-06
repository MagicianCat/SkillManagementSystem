package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.knowledge.domain.EmbeddingProviderException;
import com.company.skillplatform.ops.OpsEventService;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class KnowledgeIndexEventListener {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexEventListener.class);
    private static final int MAX_RETRIES = 10;
    private static final long BASE_DELAY_MS = 60_000;
    private static final long MAX_DELAY_MS = 3_600_000;
    private static final Object[] LOCKS = new Object[64];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    private final KnowledgeIndexService index;
    private final NotificationService notifications;
    private final Clock clock;private final JdbcTemplate db;private final OpsEventService ops;
    private final Map<Long, Retry> pending = new ConcurrentHashMap<>();
    private final Set<Long> exhausted = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean providerQuotaExhausted = new AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeIndexEventListener(KnowledgeIndexService index, NotificationService notifications,JdbcTemplate db,OpsEventService ops) {
        this(index,notifications,Clock.systemUTC(),db,ops);
    }
    public KnowledgeIndexEventListener(KnowledgeIndexService index, NotificationService notifications) {this(index,notifications,Clock.systemUTC(),null,null);}

    KnowledgeIndexEventListener(KnowledgeIndexService index, NotificationService notifications, Clock clock){this(index,notifications,clock,null,null);}
    KnowledgeIndexEventListener(KnowledgeIndexService index, NotificationService notifications, Clock clock,JdbcTemplate db,OpsEventService ops) {
        this.index=index;this.notifications=notifications;this.clock=clock;this.db=db;this.ops=ops;
    }

    @Async("knowledgeIndexExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(WikiKnowledgeChangedEvent event) {
        synchronized (lock(event.documentId())) {
            if (providerQuotaExhausted.get() && event.operation() != WikiKnowledgeChangedEvent.Operation.DELETE) {
                log.warn("event=knowledge.index.skipped documentId={} errorCode=provider_quota_exceeded", event.documentId());
                return;
            }
            exhausted.remove(event.documentId());
            pending.remove(event.documentId());
            persist(event.documentId(),"PENDING",0,clock.instant().plusSeconds(60),null);
            process(event.documentId(), 0, event.operation() == WikiKnowledgeChangedEvent.Operation.DELETE);
        }
    }

    public void scheduleRetry(Long documentId) {
        synchronized (lock(documentId)) {
            if (!providerQuotaExhausted.get() && !exhausted.contains(documentId)) {
                pending.putIfAbsent(documentId, new Retry(0, clock.instant().plusMillis(BASE_DELAY_MS), false));persist(documentId,"PENDING",0,clock.instant().plusMillis(BASE_DELAY_MS),null);
            }
        }
    }

    public boolean isExhausted(Long documentId) { return exhausted.contains(documentId); }
    public boolean isScheduled(Long documentId) { return pending.containsKey(documentId); }
    public boolean isProviderQuotaExhausted() { return providerQuotaExhausted.get(); }

    /** Re-enable indexing only when an administrator explicitly requests reconciliation. */
    public void resetForManualReconcile() {
        providerQuotaExhausted.set(false);
        exhausted.clear();
        pending.clear();
        if(db!=null)db.update("update ops_index_state set status='PENDING',next_retry_at=now(3),lease_until=null,time_updated=now(3) where status='EXHAUSTED' and error_code='EMBEDDING_QUOTA_EXHAUSTED'");
    }

    public void stopForQuota(Long documentId, EmbeddingProviderException failure) {
        pending.entrySet().removeIf(entry -> !entry.getValue().delete());
        exhausted.add(documentId);persist(documentId,"EXHAUSTED",MAX_RETRIES,null,"EMBEDDING_QUOTA_EXHAUSTED");
        if (providerQuotaExhausted.compareAndSet(false, true)) {
            log.error("event=knowledge.index.quota_exhausted documentId={} errorCode={} httpStatus={} automaticRetryStopped=true",
                    documentId, failure.providerCode(), failure.statusCode());
            try {
                notifications.wikiIndexQuotaExhausted(documentId, failure.providerCode());
            } catch (RuntimeException notificationFailure) {
                log.error("event=knowledge.index.failure_notification_failed documentId={} errorType={}",
                        documentId, notificationFailure.getClass().getSimpleName(), notificationFailure);
            }
        }
    }

    @Scheduled(fixedDelayString = "${knowledge.index.retry-check-ms:1000}")
    public void retryFailed() {
        Instant now=clock.instant();
        if(db!=null){
            for(Long documentId:db.query("select document_id from ops_index_state where status='PENDING' and next_retry_at<=now(3) and (lease_until is null or lease_until<now(3)) order by next_retry_at limit 20",(r,n)->r.getLong(1))){
                if(db.update("update ops_index_state set lease_until=date_add(now(3),interval 60 second) where document_id=? and status='PENDING' and next_retry_at<=now(3) and (lease_until is null or lease_until<now(3))",documentId)>0){
                    Integer attempts=db.queryForObject("select attempts from ops_index_state where document_id=?",Integer.class,documentId);process(documentId,attempts==null?0:attempts+1,false);
                }
            }
            return;
        }
        for(Long documentId:pending.keySet())synchronized(lock(documentId)){Retry retry=pending.get(documentId);if(retry!=null&&!now.isBefore(retry.nextAt()))process(documentId,retry.attempts()+1,retry.delete());}
    }

    private void process(Long documentId, int attempts, boolean delete) {
        if (providerQuotaExhausted.get() && !delete) {
            pending.remove(documentId);
            return;
        }
        try {
            index.reindex(documentId);
            pending.remove(documentId);
            exhausted.remove(documentId);persist(documentId,"SUCCEEDED",0,null,null);
            if(ops!=null)ops.record(new OpsEventService.Event(null,"knowledge.index.document","WIKI_INDEX","index","SUCCESS","GLOBAL",null,null,null,null,null,null,null));
        } catch (RuntimeException failure) {
            if (failure instanceof EmbeddingProviderException provider && provider.isQuotaExhausted()) {
                stopForQuota(documentId, provider);
                return;
            }
            if (attempts >= MAX_RETRIES) {
                pending.remove(documentId);
                exhausted.add(documentId);
                String errorCode=errorCode(failure);persist(documentId,"EXHAUSTED",attempts,null,errorCode);
                log.error("event=knowledge.index.retries_exhausted documentId={} attempts={} errorType={} errorCode={}",
                        documentId, attempts, failure.getClass().getSimpleName(), errorCode);
                try {
                    notifications.wikiIndexFailed(documentId, errorCode);
                } catch (RuntimeException notificationFailure) {
                    log.error("event=knowledge.index.failure_notification_failed documentId={} errorType={}",
                            documentId, notificationFailure.getClass().getSimpleName(), notificationFailure);
                }
            } else {
                long delay = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1L << attempts));
                pending.put(documentId, new Retry(attempts, clock.instant().plusMillis(delay), delete));persist(documentId,"PENDING",attempts,clock.instant().plusMillis(delay),errorCode(failure));
                log.warn("event=knowledge.index.retry_scheduled documentId={} retries={} nextDelayMs={} errorType={} errorCode={}",
                        documentId, attempts, delay, failure.getClass().getSimpleName(), errorCode(failure));
            }
        }
    }

    private void persist(Long documentId,String status,int attempts,Instant next,String code){
        if(db==null)return;
        try{db.update("insert into ops_index_state(document_id,status,attempts,next_retry_at,error_code,time_created,time_updated) values(?,?,?,?,?,now(3),now(3)) on duplicate key update status=values(status),attempts=values(attempts),next_retry_at=values(next_retry_at),lease_until=null,error_code=values(error_code),last_success_at=if(values(status)='SUCCEEDED',now(3),last_success_at),time_updated=now(3)",documentId,status,attempts,next==null?null:java.sql.Timestamp.from(next),code);}
        catch(Exception ex){log.error("[Yanshu][WIKI_INDEX] event=knowledge.index.state_write_failed documentId={}",documentId,ex);}
    }

    private String errorCode(RuntimeException failure) {
        return failure instanceof EmbeddingProviderException provider ? provider.providerCode() : failure.getClass().getSimpleName();
    }

    private Object lock(Long documentId) {
        return LOCKS[Math.floorMod(documentId.hashCode(), LOCKS.length)];
    }

    private record Retry(int attempts, Instant nextAt, boolean delete) {}
}
