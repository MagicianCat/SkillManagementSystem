package com.company.skillplatform.project.application;

import com.company.skillplatform.project.infrastructure.entity.DocumentAgentSessionEntity;
import com.company.skillplatform.project.infrastructure.repository.DocumentAgentSessionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Closes idle sessions while retaining their audit/job history for the configured window. */
@Component
public class DocumentAgentRetentionWorker {
    private final DocumentAgentSessionRepository sessions;
    public DocumentAgentRetentionWorker(DocumentAgentSessionRepository sessions) { this.sessions = sessions; }

    @Scheduled(fixedDelayString = "${skill-platform.document-agent.retention-scan-ms:3600000}")
    @Transactional
    public void process() {
        Instant now = Instant.now();
        for (DocumentAgentSessionEntity session : sessions.findByStatusInAndLastActivityAtBefore(List.of("CREATING", "ACTIVE"), now.minus(30, ChronoUnit.DAYS))) session.expire(now);
        sessions.deleteHistoryBefore(now.minus(180, ChronoUnit.DAYS));
    }
}
