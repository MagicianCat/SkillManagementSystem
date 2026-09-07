package com.company.skillplatform.auth.infrastructure;

import com.company.skillplatform.auth.domain.AuthenticatedUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;
    @Autowired
    public JwtTokenService(JwtProperties properties) { this(properties, Clock.systemUTC()); }
    JwtTokenService(JwtProperties properties, Clock clock) {
        this.properties = properties; this.clock = clock;
        this.key = Keys.hmacShaKeyFor(properties.signingKey().getBytes(StandardCharsets.UTF_8));
    }
    public String createAccessToken(AuthenticatedUser user) {
        Instant now = clock.instant();
        return Jwts.builder().issuer(properties.issuer()).subject(user.id().toString())
                .claim("username", user.username()).claim("roles", user.roles())
                .claim("permissions", user.permissions()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTtl()))).signWith(key).compact();
    }
    public String createSignedState(String provider, String redirectPath, String nonce, Duration ttl) {
        Instant now = clock.instant();
        return Jwts.builder().issuer(properties.issuer()).subject(nonce)
                .claim("typ", "oauth_state").claim("provider", provider)
                .claim("redirect_path", redirectPath).claim("nonce", nonce)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl)))
                .signWith(key).compact();
    }
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant())).build()
                .parseSignedClaims(token).getPayload();
    }
    public long accessExpiresInSeconds() { return properties.accessTtl().toSeconds(); }
    public Instant refreshExpiresAt() { return clock.instant().plus(properties.refreshTtl()); }
}
