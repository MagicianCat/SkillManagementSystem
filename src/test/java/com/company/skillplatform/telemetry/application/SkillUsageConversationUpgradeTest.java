package com.company.skillplatform.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.telemetry.infrastructure.TelemetryCipher;
import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageEventEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageConversationRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillUsageConversationUpgradeTest {
    @Test
    void acceptsLateFullConversationForTheSameSkillEvent() throws Exception {
        SkillUsageEventRepository events = mock(SkillUsageEventRepository.class);
        SkillUsageConversationRepository conversations = mock(SkillUsageConversationRepository.class);
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        TelemetryCipher cipher = mock(TelemetryCipher.class);
        SkillUsageEventEntity event = mock(SkillUsageEventEntity.class);
        when(event.getUserId()).thenReturn(7L);
        when(event.getClientSessionId()).thenReturn("session-1");
        when(events.findByEventUuid("event-1")).thenReturn(Optional.of(event));
        when(cipher.encryptBytes(any(byte[].class))).thenAnswer(call -> call.getArgument(0));
        var service = new SkillUsageConversationService(events, conversations, storage, cipher, new ObjectMapper());

        service.stage("event-1", 7L, gzip("{\"messages\":[{\"id\":\"m1\",\"content\":\"first\"}]}"));
        service.stage("event-1", 7L, gzip("{\"messages\":[{\"id\":\"m1\",\"content\":\"full\"}]}"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(storage, times(2)).put(keys.capture(), any(), anyLong(), anyString());
        List<String> stored = keys.getAllValues();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0)).isNotEqualTo(stored.get(1));
        verify(event, times(2)).stageConversation(anyString());
    }

    private byte[] gzip(String json) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
