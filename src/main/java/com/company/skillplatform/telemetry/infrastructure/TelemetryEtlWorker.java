package com.company.skillplatform.telemetry.infrastructure;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the telemetry mart synchronization off the scheduler thread every five minutes. */
@Component
public class TelemetryEtlWorker {
    private static final Logger log = LoggerFactory.getLogger(TelemetryEtlWorker.class);
    private final TelemetryEtlService etl;
    private final AtomicBoolean running = new AtomicBoolean();

    public TelemetryEtlWorker(TelemetryEtlService etl) { this.etl = etl; }

    @Scheduled(fixedDelayString = "${skill-platform.telemetry.etl-delay-ms:300000}", initialDelayString = "${skill-platform.telemetry.etl-initial-delay-ms:300000}")
    @Async
    public void synchronize() {
        if (!running.compareAndSet(false, true)) return;
        try {
            etl.synchronize();
            log.info("event=telemetry.etl.completed");
        } catch (RuntimeException e) {
            log.error("event=telemetry.etl.failed", e);
        } finally {
            running.set(false);
        }
    }
}
