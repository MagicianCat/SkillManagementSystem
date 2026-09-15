package com.company.skillplatform.auth.application;

import com.company.skillplatform.common.application.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class IdeAuthorizationRateLimiter {
    private static final long WINDOW_SECONDS = 60;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final int createLimit;
    private final int approveLimit;
    private final int pollLimit;
    private final int pollIntervalSeconds;
    private final int maxEntries;
    private final LinkedHashMap<String, Entry> state = new LinkedHashMap<>(128, .75f, true);

    @Autowired
    public IdeAuthorizationRateLimiter(MeterRegistry metrics,
            @Value("${auth.ide.rate-limit.create-per-minute:10}") int createLimit,
            @Value("${auth.ide.rate-limit.approve-per-minute:10}") int approveLimit,
            @Value("${auth.ide.rate-limit.poll-per-minute:30}") int pollLimit,
            @Value("${auth.ide.poll-interval-seconds:5}") int pollIntervalSeconds,
            @Value("${auth.ide.rate-limit.max-entries:10000}") int maxEntries) {
        this(Clock.systemUTC(), metrics, createLimit, approveLimit, pollLimit, pollIntervalSeconds, maxEntries);
    }

    IdeAuthorizationRateLimiter(Clock clock, MeterRegistry metrics, int createLimit, int approveLimit,
                                int pollLimit, int pollIntervalSeconds, int maxEntries) {
        this.clock=clock;this.metrics=metrics;this.createLimit=createLimit;this.approveLimit=approveLimit;
        this.pollLimit=pollLimit;this.pollIntervalSeconds=pollIntervalSeconds;this.maxEntries=Math.max(1,maxEntries);
        metrics.gauge("auth.ide.rate_limit.state.size", state, Map::size);
    }

    public void checkCreate(String ip) { check("create", safe(ip), createLimit, 0); }
    public void checkApprove(String ip) { check("approve", safe(ip), approveLimit, 0); }
    public void checkPoll(String ip, String deviceCode) { check("poll", safe(ip)+":"+hash(deviceCode), pollLimit, pollIntervalSeconds); }
    synchronized int stateSize(){return state.size();}

    private synchronized void check(String operation,String subject,int limit,int minimumIntervalSeconds){
        Instant now=clock.instant();String key=operation+":"+subject;Entry entry=state.get(key);
        if(entry==null||!entry.windowStart().plusSeconds(WINDOW_SECONDS).isAfter(now))entry=new Entry(now,0,null);
        if(minimumIntervalSeconds>0&&entry.lastAttempt()!=null&&entry.lastAttempt().plusSeconds(minimumIntervalSeconds).isAfter(now)){
            rejected(operation);throw error("IDE_AUTHORIZATION_SLOW_DOWN","Polling too quickly; retry after the advertised interval");
        }
        if(entry.count()>=limit){rejected(operation);throw error("IDE_AUTHORIZATION_RATE_LIMITED","Too many IDE authorization requests");}
        state.put(key,new Entry(entry.windowStart(),entry.count()+1,now));
        while(state.size()>maxEntries)state.remove(state.keySet().iterator().next());
    }
    private void rejected(String operation){metrics.counter("auth.ide.rate_limit.rejected","operation",operation).increment();}
    private String safe(String value){return value==null||value.isBlank()?"unknown":value;}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private BusinessException error(String code,String message){return new BusinessException(code,message,HttpStatus.TOO_MANY_REQUESTS);}
    private record Entry(Instant windowStart,int count,Instant lastAttempt){}
}
