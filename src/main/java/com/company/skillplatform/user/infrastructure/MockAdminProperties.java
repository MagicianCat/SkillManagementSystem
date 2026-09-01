package com.company.skillplatform.user.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skill-platform.auth.mock")
public record MockAdminProperties(boolean enabled, String adminUsername, String adminPassword, String adminDisplayName) {
}
