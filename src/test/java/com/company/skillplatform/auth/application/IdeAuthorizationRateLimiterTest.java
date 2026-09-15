package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.common.application.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IdeAuthorizationRateLimiterTest {
    @Test void limitsCreateByIpAndRecordsMetric() {
        MutableClock clock=new MutableClock();SimpleMeterRegistry metrics=new SimpleMeterRegistry();
        IdeAuthorizationRateLimiter limiter=new IdeAuthorizationRateLimiter(clock,metrics,2,2,10,5,100);
        limiter.checkCreate("127.0.0.1");limiter.checkCreate("127.0.0.1");
        assertCode(()->limiter.checkCreate("127.0.0.1"),"IDE_AUTHORIZATION_RATE_LIMITED");
        assertThat(metrics.counter("auth.ide.rate_limit.rejected","operation","create").count()).isEqualTo(1);
    }

    @Test void rejectsFastPollingAsSlowDownButAllowsAfterInterval() {
        MutableClock clock=new MutableClock();
        IdeAuthorizationRateLimiter limiter=new IdeAuthorizationRateLimiter(clock,new SimpleMeterRegistry(),10,10,10,5,100);
        limiter.checkPoll("127.0.0.1","device-hash");
        assertCode(()->limiter.checkPoll("127.0.0.1","device-hash"),"IDE_AUTHORIZATION_SLOW_DOWN");
        clock.advanceSeconds(5);limiter.checkPoll("127.0.0.1","device-hash");
    }

    @Test void keepsStateBounded() {
        IdeAuthorizationRateLimiter limiter=new IdeAuthorizationRateLimiter(new MutableClock(),new SimpleMeterRegistry(),10,10,10,5,3);
        for(int i=0;i<20;i++)limiter.checkCreate("ip-"+i);
        assertThat(limiter.stateSize()).isLessThanOrEqualTo(3);
    }

    private static void assertCode(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(code));}
    private static final class MutableClock extends Clock{
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-09-11T00:00:00Z"));
        void advanceSeconds(long seconds){now.updateAndGet(value->value.plusSeconds(seconds));}
        @Override public ZoneId getZone(){return ZoneId.of("UTC");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now.get();}
    }
}
