package com.company.skillplatform.telemetry.infrastructure;

import com.company.skillplatform.telemetry.application.SkillUsageConversationService;
import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageEventEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SkillUsageConversationWorker {
    private final SkillUsageEventRepository events; private final SkillUsageConversationService service;
    public SkillUsageConversationWorker(SkillUsageEventRepository events, SkillUsageConversationService service) { this.events = events; this.service = service; }
    @Scheduled(fixedDelayString = "${skill-platform.telemetry.worker-delay-ms:5000}")
    public void process() {
        List<SkillUsageEventEntity> pending = events.findTop50ByConversationStatusAndConversationAttemptsLessThanOrderByTimeCreatedAsc("STAGED", 5);
        for (SkillUsageEventEntity event : pending) {
            int attempt = service.incrementAttempt(event.getEventUuid());
            try {
                service.merge(event.getEventUuid());
            } catch (RuntimeException ex) {
                if (attempt >= 5) service.fail(event.getEventUuid(), "CONVERSATION_MERGE_FAILED");
            }
        }
    }
}
