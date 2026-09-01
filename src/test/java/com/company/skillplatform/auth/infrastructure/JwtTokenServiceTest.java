package com.company.skillplatform.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.skillplatform.auth.domain.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {
    @Test void createsAndParsesSignedAccessToken() {
        Instant now = Instant.parse("2026-09-01T08:00:00Z");
        JwtProperties properties = new JwtProperties("issuer", "01234567890123456789012345678901",
                Duration.ofMinutes(30), Duration.ofDays(7));
        JwtTokenService service = new JwtTokenService(properties, Clock.fixed(now, ZoneOffset.UTC));
        String token = service.createAccessToken(new AuthenticatedUser(7L, "user", "User", List.of("ADMIN"), List.of("skill:browse")));
        assertThat(service.parse(token).getSubject()).isEqualTo("7");
        assertThat(service.parse(token).get("username", String.class)).isEqualTo("user");
        assertThat(service.accessExpiresInSeconds()).isEqualTo(1800);
        assertThat(service.refreshExpiresAt()).isEqualTo(now.plus(Duration.ofDays(7)));
    }
}
