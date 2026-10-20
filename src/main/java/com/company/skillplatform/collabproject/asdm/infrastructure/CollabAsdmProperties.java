package com.company.skillplatform.collabproject.asdm.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("skill-platform.asdm-gitlab")
public record CollabAsdmProperties(
        boolean enabled,
        List<String> allowedHosts,
        String tokenEncryptionKey,
        Duration pollInterval,
        Duration debounce,
        Duration connectTimeout,
        Duration readTimeout,
        int maxMarkdownBytes,
        int maxTreeEntries,
        int pollBatchSize) {
    public CollabAsdmProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream()
                .map(s -> s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(s -> !s.isBlank()).distinct().toList();
        pollInterval = pollInterval == null ? Duration.ofSeconds(60) : pollInterval;
        debounce = debounce == null ? Duration.ofSeconds(300) : debounce;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(20) : readTimeout;
        if (maxMarkdownBytes <= 0) maxMarkdownBytes = 1_048_576;
        if (maxTreeEntries <= 0) maxTreeEntries = 5_000;
        if (pollBatchSize <= 0) pollBatchSize = 20;
    }
}
