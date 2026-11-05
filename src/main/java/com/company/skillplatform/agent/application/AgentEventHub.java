package com.company.skillplatform.agent.application;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentEventHub {
    public record Event(long sequence,String type,Map<String,Object> data){}
    private static final int MAX_EVENTS=2000;
    private static final long TERMINAL_RETENTION_MS=10*60*1000L;
    private final ConcurrentMap<String,State> states=new ConcurrentHashMap<>();
    public Event publish(String runKey,String type,Map<String,Object> data){
        State state=states.computeIfAbsent(runKey,k->new State());
        Event event=new Event(state.sequence.incrementAndGet(),type,Map.copyOf(data));
        synchronized(state.events){state.events.addLast(event);while(state.events.size()>MAX_EVENTS)state.events.removeFirst();}
        for(SseEmitter emitter:state.emitters)send(emitter,event,state);
        if(type.startsWith("run.")&&(type.endsWith("completed")||type.endsWith("failed")||type.endsWith("cancelled"))) complete(state);
        return event;
    }
    public SseEmitter subscribe(String runKey,long after){
        State state=states.computeIfAbsent(runKey,k->new State());
        SseEmitter emitter=new SseEmitter(0L);
        emitter.onCompletion(()->state.emitters.remove(emitter)); emitter.onTimeout(()->state.emitters.remove(emitter));
        synchronized(state.events){for(Event event:state.events)if(event.sequence()>after)send(emitter,event,state);if(state.terminal&&after>=state.sequence.get()&&state.events.size()==1)send(emitter,state.events.getLast(),state);}
        if(!state.terminal)state.emitters.add(emitter);else emitter.complete();
        return emitter;
    }
    public void restoreTerminal(String runKey,String type,Map<String,Object>data){
        State state=states.computeIfAbsent(runKey,k->new State());
        if(state.sequence.get()==0&&!state.terminal)publish(runKey,type,data);
    }
    @Scheduled(fixedDelay=60_000)
    public void evictExpired(){long now=System.currentTimeMillis();states.entrySet().removeIf(entry->entry.getValue().terminal&&entry.getValue().emitters.isEmpty()&&now-entry.getValue().terminalAt>TERMINAL_RETENTION_MS);}
    private void send(SseEmitter emitter,Event event,State state){try{emitter.send(SseEmitter.event().id(String.valueOf(event.sequence())).name(event.type()).data(event));}catch(IOException e){state.emitters.remove(emitter);emitter.completeWithError(e);}}
    private void complete(State state){state.terminalAt=System.currentTimeMillis();state.terminal=true;for(SseEmitter emitter:state.emitters)emitter.complete();state.emitters.clear();}
    private static final class State{final AtomicLong sequence=new AtomicLong();final Deque<Event>events=new ArrayDeque<>();final Set<SseEmitter>emitters=new CopyOnWriteArraySet<>();volatile boolean terminal;volatile long terminalAt;}
}
