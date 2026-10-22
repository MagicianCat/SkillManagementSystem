package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.notification.application.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Clock clock;
    private final Map<Long, Retry> pending = new ConcurrentHashMap<>();
    private final Set<Long> exhausted = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeIndexEventListener(KnowledgeIndexService index, NotificationService notifications) {
        this(index, notifications, Clock.systemUTC());
    }

    KnowledgeIndexEventListener(KnowledgeIndexService index, NotificationService notifications, Clock clock) {
        this.index = index;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Async("knowledgeIndexExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(WikiKnowledgeChangedEvent event) {
        synchronized (lock(event.documentId())) {
            exhausted.remove(event.documentId());
            pending.remove(event.documentId());
            process(event.documentId(), 0);
        }
    }

    public void scheduleRetry(Long documentId) {
        synchronized (lock(documentId)) {
            if (!exhausted.contains(documentId)) {
                pending.putIfAbsent(documentId, new Retry(0, clock.instant().plusMillis(BASE_DELAY_MS)));
            }
        }
    }

    public boolean isExhausted(Long documentId) { return exhausted.contains(documentId); }
    public boolean isScheduled(Long documentId) { return pending.containsKey(documentId); }

    @Scheduled(fixedDelayString = "${knowledge.index.retry-check-ms:1000}")
    public void retryFailed() {
        Instant now = clock.instant();
        for (Long documentId : pending.keySet()) {
            synchronized (lock(documentId)) {
                Retry retry = pending.get(documentId);
                if (retry != null && !now.isBefore(retry.nextAt())) {
                    process(documentId, retry.attempts() + 1);
                }
            }
        }
    }

    private void process(Long documentId, int attempts) {
        try {
            index.reindex(documentId);
            pending.remove(documentId);
            exhausted.remove(documentId);
        } catch (RuntimeException failure) {
            if (attempts >= MAX_RETRIES) {
                pending.remove(documentId);
                exhausted.add(documentId);
                log.error("event=knowledge.index.retries_exhausted documentId={} attempts={} errorType={}",
                        documentId, attempts, failure.getClass().getSimpleName());
                try {
                    notifications.wikiIndexFailed(documentId);
                } catch (RuntimeException notificationFailure) {
                    log.error("event=knowledge.index.failure_notification_failed documentId={} errorType={}",
                            documentId, notificationFailure.getClass().getSimpleName(), notificationFailure);
                }
            } else {
                long delay = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1L << attempts));
                pending.put(documentId, new Retry(attempts, clock.instant().plusMillis(delay)));
                log.warn("event=knowledge.index.retry_scheduled documentId={} retries={} nextDelayMs={} errorType={}",
                        documentId, attempts, delay, failure.getClass().getSimpleName());
            }
        }
    }

    private Object lock(Long documentId) {
        return LOCKS[Math.floorMod(documentId.hashCode(), LOCKS.length)];
    }

    private record Retry(int attempts, Instant nextAt) {}
}
