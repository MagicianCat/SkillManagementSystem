package com.company.skillplatform.agent.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentRun(String runRef, Long userId, String profileKey, String platform,
                       String osType, Instant expiresAt, String status) {
    public static AgentRun create(Long userId, String profileKey, String platform, String osType, Instant expiresAt) {
        return new AgentRun(UUID.randomUUID().toString(), userId, profileKey, platform, osType, expiresAt, "ACTIVE");
    }
}
