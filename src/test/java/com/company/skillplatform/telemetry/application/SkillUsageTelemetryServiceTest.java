package com.company.skillplatform.telemetry.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.telemetry.infrastructure.TelemetryCipher;
import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageEventEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.version.infrastructure.repository.SkillVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void enrichesAnExistingLogEventWithStructuredModelAndProject() {
        IamUserEntity user = mock(IamUserEntity.class);
        when(user.getId()).thenReturn(7L);
        SkillEntity skill = mock(SkillEntity.class);
        String eventId = UUID.randomUUID().toString();
        SkillUsageEventEntity existing = new SkillUsageEventEntity(eventId, user, skill, null,
                "code-review", null, "ciphertext", "ciphertext", "UNKNOWN", "session-1", "log-request-1",
                "CodeBuddyIDE", null, null, null, Instant.parse("2026-10-26T01:00:00Z"), Instant.now());
        when(events.findByEventUuid(eventId)).thenReturn(Optional.of(existing));
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(7L, new SkillUsageTelemetryService.CreateCommand(eventId, "code-review", null, null,
                "D:/project", "session-1", "log-request-1", "CodeBuddyIDE", null, null, "GLM-5.3",
                Instant.parse("2026-10-26T01:00:00Z")));

        verify(events).saveAndFlush(existing);
        org.assertj.core.api.Assertions.assertThat(ReflectionTestUtils.getField(existing, "model")).isEqualTo("GLM-5.3");
        org.assertj.core.api.Assertions.assertThat(ReflectionTestUtils.getField(existing, "localDirectory")).isEqualTo("D:/project");
    }
}
