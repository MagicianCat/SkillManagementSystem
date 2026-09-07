package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.domain.AgentRun;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.company.skillplatform.common.application.BusinessException;

@Service
public class AgentRunService {
    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final SecretKey key;
    private final Clock clock = Clock.systemUTC();
    private final Duration ttl;

    public AgentRunService(@Value("${agent.mcp.signing-key:${JWT_SIGNING_KEY:SkillManagementJwtSigningKey-2026-AtLeast32Bytes}}") String signingKey,
                           @Value("${agent.mcp.run-ttl:PT30M}") Duration ttl) {
        this.key = Keys.hmacShaKeyFor(signingKey.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
    }
    public IssuedRun issue(Long userId, String profileKey, String platform, String osType) {
        Instant expires = clock.instant().plus(ttl);
        AgentRun run = AgentRun.create(userId, profileKey, platform, osType, expires);
        runs.put(run.runRef(), run);
        String token = Jwts.builder().issuer("skill-platform-agent").subject(String.valueOf(userId))
                .claim("runRef", run.runRef()).claim("profileKey", profileKey)
                .claim("capabilities", java.util.List.of("skill.search", "skill.detail", "skill.recommendation.submit"))
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(expires)).signWith(key).compact();
        return new IssuedRun(run, token);
    }
    public AgentRun require(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer("skill-platform-agent")
                    .clock(() -> Date.from(clock.instant())).build().parseSignedClaims(token).getPayload();
            AgentRun run = runs.get(claims.get("runRef", String.class));
            if (run == null || !"ACTIVE".equals(run.status()) || run.expiresAt().isBefore(clock.instant()))
                throw new BusinessException("AGENT_RUN_INVALID", "Agent run is invalid or expired", HttpStatus.UNAUTHORIZED);
            return run;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("AGENT_TOKEN_INVALID", "Invalid agent run token", HttpStatus.UNAUTHORIZED); }
    }
    public record IssuedRun(AgentRun run, String token) {}
}
