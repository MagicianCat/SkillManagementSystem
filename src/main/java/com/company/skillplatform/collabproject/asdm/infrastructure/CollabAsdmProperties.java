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
        int pollBatchSize,
        boolean multiStageEnabled,
        boolean multiFilePublishEnabled,
        int maxDiscoveryEntries,
        int maxCandidateFiles,
        long maxFileBytes,
        long maxPackageBytes,
        boolean matchLikelyAutoinclude,
        int filePublishWorkers,
        int filePublishRetryLimit,
        Duration blobCacheTtl) {
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
        if (maxDiscoveryEntries <= 0) maxDiscoveryEntries = 20_000;
        if (maxCandidateFiles <= 0) maxCandidateFiles = 300;
        if (maxFileBytes <= 0) maxFileBytes = 20L * 1024 * 1024;
        if (maxPackageBytes <= 0) maxPackageBytes = 200L * 1024 * 1024;
        if (filePublishWorkers <= 0) filePublishWorkers = 4;
        if (filePublishRetryLimit <= 0) filePublishRetryLimit = 8;
        blobCacheTtl = blobCacheTtl == null ? Duration.ofHours(24) : blobCacheTtl;
    }
}
