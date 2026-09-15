package com.company.skillplatform.telemetry.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.telemetry.infrastructure.TelemetryCipher;
import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageEventEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillUsageTelemetryService {
    private final SkillUsageEventRepository events;
    private final IamUserRepository users;
    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final TelemetryCipher cipher;
    private final Clock clock = Clock.systemUTC();

    public SkillUsageTelemetryService(SkillUsageEventRepository events, IamUserRepository users,
            SkillRepository skills, SkillVersionRepository versions, TelemetryCipher cipher) {
        this.events = events; this.users = users; this.skills = skills; this.versions = versions; this.cipher = cipher;
    }

    @Transactional
    public EventView record(Long userId, CreateCommand command) {
        validateUuid(command.eventUuid());
        return events.findByEventUuid(command.eventUuid()).map(this::view).orElseGet(() -> {
            IamUserEntity user = users.findById(userId).orElseThrow(() -> error("USER_NOT_FOUND", HttpStatus.UNAUTHORIZED));
            SkillEntity skill = skills.findBySkillKey(command.skillKey()).orElseThrow(() -> error("SKILL_NOT_FOUND", HttpStatus.UNPROCESSABLE_ENTITY));
            if (command.skillVersionId() != null) {
                versions.findById(command.skillVersionId())
                        .filter(version -> version.getSkill().getId().equals(skill.getId()))
                        .orElseThrow(() -> error("SKILL_VERSION_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY));
            }
            Instant now = clock.instant();
            SkillUsageEventEntity event = new SkillUsageEventEntity(command.eventUuid(), user, skill, command.skillVersionId(),
                    command.skillKey(), command.installationId(), cipher.encrypt(value(user.getFeishuUserId())),
                    cipher.encrypt(value(user.getFeishuOpenId())), command.localDirectory(), command.clientSessionId(),
                    command.generationId(), command.client(), command.clientVersion(), command.agentType(), command.model(),
                    command.invokedAt() == null ? now : command.invokedAt(), now);
            return view(events.saveAndFlush(event));
        });
    }

    private String value(String value) { return value == null ? "" : value; }
    private void validateUuid(String value) { try { UUID.fromString(value); } catch (Exception ex) { throw error("EVENT_ID_INVALID", HttpStatus.BAD_REQUEST); } }
    private BusinessException error(String code, HttpStatus status) { return new BusinessException(code, code, status); }
    private EventView view(SkillUsageEventEntity event) { return new EventView(event.getEventUuid(), event.getSkillKeySnapshot(), event.getConversationStatus(), event.getInvokedAt()); }
    public record CreateCommand(String eventUuid, String skillKey, Long skillVersionId, String installationId,
            String localDirectory, String clientSessionId, String generationId, String client, String clientVersion,
            String agentType, String model, Instant invokedAt) {}
    public record EventView(String eventId, String skillKey, String conversationStatus, Instant invokedAt) {}
}
