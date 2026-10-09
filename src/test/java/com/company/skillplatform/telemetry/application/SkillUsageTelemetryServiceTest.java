package com.company.skillplatform.telemetry.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.telemetry.infrastructure.TelemetryCipher;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillUsageTelemetryServiceTest {
    private final SkillUsageEventRepository events = mock(SkillUsageEventRepository.class);
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillVersionRepository versions = mock(SkillVersionRepository.class);
    private final TelemetryCipher cipher = mock(TelemetryCipher.class);
    private final SkillUsageTelemetryService service = new SkillUsageTelemetryService(events, users, skills, versions, cipher);

    @Test
    void linksSkillEventWhenGenerationArrivedFirst() {
        IamUserEntity user = mock(IamUserEntity.class);
        SkillEntity skill = mock(SkillEntity.class);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(skills.findBySkillKey("code-review")).thenReturn(Optional.of(skill));
        when(events.findByEventUuid(any())).thenReturn(Optional.empty());
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cipher.encrypt(any())).thenReturn("ciphertext");

        String eventId = UUID.randomUUID().toString();
        service.record(7L, new SkillUsageTelemetryService.CreateCommand(
                eventId, "code-review", null, "install-1", "D:/project", "session-1", "generation-1",
                "CodeBuddyIDE", "0.5.3", null, null, Instant.parse("2026-10-09T01:00:00Z")));

        verify(events).linkGenerationByExternalId(7L, "generation-1");
    }
}
