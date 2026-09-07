package com.company.skillplatform.agent.application;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentEventHub {
    public record Event(long sequence,String type,Map<String,Object> data){}
    private static final int MAX_EVENTS=2000;
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
        synchronized(state.events){for(Event event:state.events)if(event.sequence()>after)send(emitter,event,state);}
        if(!state.terminal)state.emitters.add(emitter);else emitter.complete();
        return emitter;
    }
    public void restoreTerminal(String runKey,String type,Map<String,Object>data){
        State state=states.computeIfAbsent(runKey,k->new State());
        if(state.sequence.get()==0&&!state.terminal)publish(runKey,type,data);
    }
    private void send(SseEmitter emitter,Event event,State state){try{emitter.send(SseEmitter.event().id(String.valueOf(event.sequence())).name(event.type()).data(event));}catch(IOException e){state.emitters.remove(emitter);emitter.completeWithError(e);}}
    private void complete(State state){state.terminal=true;for(SseEmitter emitter:state.emitters)emitter.complete();state.emitters.clear();}
    private static final class State{final AtomicLong sequence=new AtomicLong();final Deque<Event>events=new ArrayDeque<>();final Set<SseEmitter>emitters=new CopyOnWriteArraySet<>();volatile boolean terminal;}
}
