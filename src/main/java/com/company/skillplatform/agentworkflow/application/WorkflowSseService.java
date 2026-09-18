package com.company.skillplatform.agentworkflow.application;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class WorkflowSseService {
    private final WorkflowRuntimeEventService events;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public WorkflowSseService(WorkflowRuntimeEventService events) { this.events = events; }

    public SseEmitter subscribe(long workflowRunId, long afterSequence, Object snapshot) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(workflowRunId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> emitters.getOrDefault(workflowRunId, new CopyOnWriteArrayList<>()).remove(emitter);
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(ignored -> remove.run());
        try {
            if (afterSequence == 0) send(emitter, "0", "workflow.snapshot", Map.of("type", "workflow.snapshot", "data", snapshot));
            for (WorkflowRuntimeEventService.StoredEvent event : events.after(workflowRunId, afterSequence)) send(emitter, event);
        } catch (IOException failure) { emitter.completeWithError(failure); }
        return emitter;
    }

    public void publish(long workflowRunId, WorkflowRuntimeEventService.StoredEvent event) {
        for (SseEmitter emitter : emitters.getOrDefault(workflowRunId, new CopyOnWriteArrayList<>())) {
            try { send(emitter, event); }
            catch (IOException failure) { emitter.complete(); }
        }
    }

    private void send(SseEmitter emitter, WorkflowRuntimeEventService.StoredEvent event) throws IOException {
        send(emitter, String.valueOf(event.sequence()), event.type(), Map.of("id", event.eventId(), "sequence", event.sequence(),
                "type", event.type(), "data", event.data(), "createdAt", event.createdAt()));
    }
    private void send(SseEmitter emitter, String id, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().id(id).name(name).data(data));
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        emitters.values().stream().flatMap(List::stream).forEach(emitter -> {
            try { emitter.send(SseEmitter.event().comment("heartbeat")); }
            catch (IOException failure) { emitter.complete(); }
        });
    }
}
