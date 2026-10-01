package com.company.skillplatform.codegraph.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("skill-platform.code-graph.worker")
public record CodeGraphWorkerProperties(boolean enabled, String baseUrl, String token,
                                        Duration connectTimeout, Duration readTimeout,
                                        int maxConcurrentBuilds, int workers,
                                        int analyzeTimeoutSeconds, int groupSyncTimeoutSeconds) {
    public CodeGraphWorkerProperties {
        baseUrl = baseUrl == null ? "http://127.0.0.1:4780" : baseUrl;
        token = token == null ? "" : token;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        maxConcurrentBuilds = maxConcurrentBuilds < 1 ? 2 : maxConcurrentBuilds;
        workers = workers < 1 ? 4 : workers;
        analyzeTimeoutSeconds = analyzeTimeoutSeconds < 60 ? 1800 : analyzeTimeoutSeconds;
        groupSyncTimeoutSeconds = groupSyncTimeoutSeconds < 30 ? 600 : groupSyncTimeoutSeconds;
    }
}
