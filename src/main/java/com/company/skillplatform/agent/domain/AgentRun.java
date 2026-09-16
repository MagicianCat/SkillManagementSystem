package com.company.skillplatform.agent.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentRun(String runRef, Long userId, String profileKey, String platform,
                       String osType, Instant expiresAt, String status, String knowledgeScope,
                       String projectKey, Long documentId) {
    public AgentRun(String runRef, Long userId, String profileKey, String platform, String osType, Instant expiresAt, String status, String knowledgeScope) {
        this(runRef, userId, profileKey, platform, osType, expiresAt, status, knowledgeScope, null, null);
    }
    public AgentRun(String runRef, Long userId, String profileKey, String platform, String osType, Instant expiresAt, String status) {
        this(runRef, userId, profileKey, platform, osType, expiresAt, status, "USER_VISIBLE");
    }
    public static AgentRun create(Long userId, String profileKey, String platform, String osType, Instant expiresAt) {
        return new AgentRun(UUID.randomUUID().toString(), userId, profileKey, platform, osType, expiresAt, "ACTIVE", "USER_VISIBLE");
    }
    public static AgentRun create(String runRef, Long userId, String profileKey, String platform, String osType, Instant expiresAt) {
        return new AgentRun(runRef, userId, profileKey, platform, osType, expiresAt, "ACTIVE", "USER_VISIBLE");
    }
}
