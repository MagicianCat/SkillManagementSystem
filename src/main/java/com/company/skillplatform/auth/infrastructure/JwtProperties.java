package com.company.skillplatform.auth.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skill-platform.auth.jwt")
public record JwtProperties(String issuer, String signingKey, Duration accessTtl, Duration refreshTtl) {
}
