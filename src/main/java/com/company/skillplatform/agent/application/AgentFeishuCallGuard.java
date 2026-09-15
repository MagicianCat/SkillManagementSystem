package com.company.skillplatform.agent.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounds Feishu retrieval work for one Agent run and prevents duplicate reads. */
@Service
public class AgentFeishuCallGuard {
    private static final Duration RETENTION = Duration.ofMinutes(10);
    @Value("${agent.feishu.max-searches:3}") private int maxSearches = 3;
    @Value("${agent.feishu.max-read-attempts:5}") private int maxReadAttempts = 5;
    @Value("${agent.feishu.max-successful-reads:3}") private int maxSuccessfulReads = 3;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public Decision beforeSearch(String runRef) {
        State state = state(runRef);
        synchronized (state) {
            if (state.searches >= maxSearches) return Decision.denied("search_budget_exhausted");
            state.searches++;
            return Decision.permitted();
        }
    }

    public Decision beforeRead(String runRef, String docId) {
        State state = state(runRef);
        synchronized (state) {
            if (state.attemptedDocIds.contains(docId)) return Decision.denied("duplicate_skipped");
            if (state.readAttempts >= maxReadAttempts) return Decision.denied("document_budget_exhausted");
            if (state.successfulDocIds.size() >= maxSuccessfulReads) return Decision.denied("evidence_budget_reached");
            state.readAttempts++;
            state.attemptedDocIds.add(docId);
            return Decision.permitted();
        }
    }

    public void readSucceeded(String runRef, String docId) {
        State state = state(runRef);
        synchronized (state) { state.successfulDocIds.add(docId); }
    }

    public void readFailed(String runRef, String docId) {
        // The attempted-doc set intentionally remains populated, preventing retries.
        state(runRef);
    }

    @Scheduled(fixedDelayString = "${agent.feishu.guard-cleanup-ms:600000}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(RETENTION);
        states.entrySet().removeIf(entry -> entry.getValue().createdAt.isBefore(cutoff));
    }

    private State state(String runRef) {
        return states.computeIfAbsent(runRef, ignored -> new State());
    }

    public record Decision(boolean allowed, String code) {
        public static Decision permitted() { return new Decision(true, null); }
        public static Decision denied(String code) { return new Decision(false, code); }
    }

    private static final class State {
        private final Instant createdAt = Instant.now();
        private int searches;
        private int readAttempts;
        private final Set<String> attemptedDocIds = ConcurrentHashMap.newKeySet();
        private final Set<String> successfulDocIds = ConcurrentHashMap.newKeySet();
    }
}
