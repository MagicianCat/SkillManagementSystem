package com.company.skillplatform.agentworkflow.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class WorkflowSseServiceTest {

    @Test
    void disconnectedEmitterDoesNotBlockHealthyEmitter() throws Exception {
        WorkflowRuntimeEventService events = mock(WorkflowRuntimeEventService.class);
        WorkflowSseService service = new WorkflowSseService(events);

        SseEmitter disconnected = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        ReflectionTestUtils.setField(service, "emitters", new java.util.concurrent.ConcurrentHashMap<>(Map.of(
                11L, new java.util.concurrent.CopyOnWriteArrayList<>(List.of(disconnected, healthy)))));
        doAnswer(invocation -> { throw new IllegalStateException("ResponseBodyEmitter has already completed"); })
                .when(disconnected).send(any(SseEmitter.SseEventBuilder.class));

        WorkflowRuntimeEventService.StoredEvent event = new WorkflowRuntimeEventService.StoredEvent(
                "event-1", 1L, "tool.started", Map.of("name", "probe"), Instant.now());

        assertDoesNotThrow(() -> service.publish(11L, event));
        verify(healthy).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void disconnectedEmitterDoesNotBreakHeartbeat() throws Exception {
        WorkflowRuntimeEventService events = mock(WorkflowRuntimeEventService.class);
        WorkflowSseService service = new WorkflowSseService(events);
        SseEmitter disconnected = mock(SseEmitter.class);
        ReflectionTestUtils.setField(service, "emitters", new java.util.concurrent.ConcurrentHashMap<>(Map.of(
                12L, new java.util.concurrent.CopyOnWriteArrayList<>(List.of(disconnected)))));
        doAnswer(invocation -> { throw new IllegalStateException("ResponseBodyEmitter has already completed"); })
                .when(disconnected).send(any(SseEmitter.SseEventBuilder.class));

        assertDoesNotThrow(service::heartbeat);
        assertDoesNotThrow(service::heartbeat);
    }
}
