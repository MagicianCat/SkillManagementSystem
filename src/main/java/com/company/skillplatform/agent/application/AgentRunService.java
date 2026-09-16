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
import com.company.skillplatform.agent.infrastructure.entity.AgentRunEntity;
import com.company.skillplatform.agent.infrastructure.repository.AgentRunRepository;
import com.company.skillplatform.project.infrastructure.repository.DocumentAgentSessionRepository;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.company.skillplatform.common.application.BusinessException;

@Service
public class AgentRunService {
    private final AgentRunRepository persistentRuns;
    private final SecretKey key;
    private final Clock clock = Clock.systemUTC();
    private final Duration ttl;
    private final Duration documentSessionTtl;
    private final DocumentAgentSessionRepository documentSessions;

    public AgentRunService(AgentRunRepository persistentRuns,
                           @Value("${agent.mcp.signing-key:${JWT_SIGNING_KEY:SkillManagementJwtSigningKey-2026-AtLeast32Bytes}}") String signingKey,
                           @Value("${agent.mcp.run-ttl:PT5M}") Duration ttl,
                           @Value("${agent.mcp.document-session-ttl:PT30D}") Duration documentSessionTtl,
                           DocumentAgentSessionRepository documentSessions) {
        this.persistentRuns = persistentRuns;
        this.key = Keys.hmacShaKeyFor(signingKey.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
        this.documentSessionTtl = documentSessionTtl;
        this.documentSessions = documentSessions;
    }
    public IssuedRun issue(Long userId, String profileKey, String platform, String osType) {
        return issue(AgentRun.create(userId, profileKey, platform, osType, clock.instant().plus(ttl)));
    }
    public IssuedRun issueForRun(String runRef, Long userId, String profileKey, String platform, String osType) {
        return issueForRun(runRef, userId, profileKey, platform, osType, "USER_VISIBLE");
    }
    public IssuedRun issueForRun(String runRef, Long userId, String profileKey, String platform, String osType, String knowledgeScope) {
        return issue(new AgentRun(runRef, userId, profileKey, platform, osType, clock.instant().plus(ttl), "ACTIVE", knowledgeScope));
    }
    public IssuedRun issueDocumentRun(Long userId, String projectKey, Long documentId, String profileKey) {
        if (!java.util.Set.of("requirement-analysis/v1", "prd-authoring/v1", "architecture-design/v1", "ui-design/v1").contains(profileKey))
            throw new BusinessException("AGENT_PROFILE_INVALID", "Unsupported document agent profile", HttpStatus.BAD_REQUEST);
        AgentRun run = new AgentRun(java.util.UUID.randomUUID().toString(), userId, profileKey, null, null,
                clock.instant().plus(ttl), "ACTIVE", "PROJECT_MEMBER", projectKey, documentId);
        String token = Jwts.builder().issuer("skill-platform-agent").subject(String.valueOf(userId))
                .claim("runRef", run.runRef()).claim("profileKey", profileKey).claim("kind", "document")
                .claim("projectKey", projectKey).claim("documentId", documentId)
                .claim("capabilities", java.util.List.of("project.context.read", "project.artifact.list", "project.artifact.read", "project.artifact.write", "project.artifact.validate"))
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(run.expiresAt())).signWith(key).compact();
        return new IssuedRun(run, token);
    }
    public IssuedRun issueDocumentSessionRun(Long userId, String projectKey, Long documentId, String profileKey, String sessionKey) {
        if (!java.util.Set.of("requirement-analysis/v1", "prd-authoring/v1", "architecture-design/v1", "ui-design/v1").contains(profileKey))
            throw new BusinessException("AGENT_PROFILE_INVALID", "Unsupported document agent profile", HttpStatus.BAD_REQUEST);
        Instant expires = clock.instant().plus(documentSessionTtl);
        AgentRun run = new AgentRun(java.util.UUID.randomUUID().toString(), userId, profileKey, null, null,
                expires, "ACTIVE", "PROJECT_MEMBER", projectKey, documentId, sessionKey);
        String token = Jwts.builder().issuer("skill-platform-agent").subject(String.valueOf(userId))
                .claim("runRef", run.runRef()).claim("profileKey", profileKey).claim("kind", "document")
                .claim("projectKey", projectKey).claim("documentId", documentId).claim("sessionKey", sessionKey)
                .claim("capabilities", java.util.List.of("project.context.read", "project.artifact.list", "project.artifact.read", "project.artifact.write", "project.artifact.validate", "feishu.read"))
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(expires)).signWith(key).compact();
        return new IssuedRun(run, token);
    }
    /** Short-lived token scoped to one durable document job dispatch. */
    public IssuedRun issueDocumentJobRun(Long userId, String projectKey, Long documentId, String profileKey, String sessionKey, String jobKey) {
        if (!java.util.Set.of("requirement-analysis/v1", "prd-authoring/v1", "architecture-design/v1", "ui-design/v1").contains(profileKey))
            throw new BusinessException("AGENT_PROFILE_INVALID", "Unsupported document agent profile", HttpStatus.BAD_REQUEST);
        Instant expires = clock.instant().plus(Duration.ofMinutes(15));
        AgentRun run = new AgentRun(jobKey, userId, profileKey, null, null, expires, "ACTIVE", "PROJECT_MEMBER", projectKey, documentId, sessionKey);
        String token = Jwts.builder().issuer("skill-platform-agent").subject(String.valueOf(userId))
                .claim("runRef", run.runRef()).claim("profileKey", profileKey).claim("kind", "document_job")
                .claim("projectKey", projectKey).claim("documentId", documentId).claim("sessionKey", sessionKey)
                .claim("jobKey", jobKey)
                .claim("capabilities", java.util.List.of("project.context.read", "project.artifact.list", "project.artifact.read", "project.artifact.write", "project.artifact.validate", "feishu.read"))
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(expires)).signWith(key).compact();
        return new IssuedRun(run, token);
    }
    private IssuedRun issue(AgentRun run) {
        String token = Jwts.builder().issuer("skill-platform-agent").subject(String.valueOf(run.userId()))
                .claim("runRef", run.runRef()).claim("profileKey", run.profileKey())
                .claim("capabilities", java.util.List.of("skill.search", "skill.detail", "skill.recommendation.submit"))
                .claim("knowledgeScope", run.knowledgeScope())
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(run.expiresAt())).signWith(key).compact();
        return new IssuedRun(run, token);
    }
    public AgentRun require(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer("skill-platform-agent")
                    .clock(() -> Date.from(clock.instant())).build().parseSignedClaims(token).getPayload();
            String runRef = claims.get("runRef", String.class);
            Long userId = Long.valueOf(claims.getSubject());
            String profile = claims.get("profileKey", String.class);
            if ("document".equals(claims.get("kind", String.class)) || "document_job".equals(claims.get("kind", String.class))) {
                String projectKey = claims.get("projectKey", String.class);
                Number documentId = claims.get("documentId", Number.class);
                String sessionKey = claims.get("sessionKey", String.class);
                if (sessionKey != null) {
                    var session = documentSessions.findForMcp(sessionKey).orElse(null);
                    if (session == null || !userId.equals(session.getOwner().getId()) || !"ACTIVE".equals(session.getStatus())
                            || session.getMcpTokenExpiresAt().isBefore(clock.instant()))
                        throw new BusinessException("AGENT_RUN_INVALID", "Document agent session is invalid or expired", HttpStatus.UNAUTHORIZED);
                }
                return new AgentRun(runRef, userId, profile, null, null, claims.getExpiration().toInstant(), "ACTIVE", "PROJECT_MEMBER", projectKey, documentId == null ? null : documentId.longValue(), sessionKey);
            }
            AgentRunEntity entity = persistentRuns.findByRunKey(runRef).orElse(null);
            if (entity == null || !"RUNNING".equals(entity.getStatus())
                    || !userId.equals(entity.getSession().getOwnerUserId())
                    || !profile.equals(entity.getSession().getProfileKey())
                    || !"ACTIVE".equals(entity.getSession().getStatus()))
                throw new BusinessException("AGENT_RUN_INVALID", "Agent run is invalid or expired", HttpStatus.UNAUTHORIZED);
            Instant expiresAt = claims.getExpiration().toInstant();
            return new AgentRun(runRef, userId, profile, entity.getSession().getPlatform(), entity.getSession().getOsType(), expiresAt, "ACTIVE", entity.getSession().getKnowledgeScope());
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("AGENT_TOKEN_INVALID", "Invalid agent run token", HttpStatus.UNAUTHORIZED); }
    }
    public void requireCapability(AgentRun run, String capability) {
        if (run.sessionKey() != null && java.util.Set.of("project.context.read", "project.artifact.list", "project.artifact.read", "project.artifact.write", "project.artifact.validate", "feishu.read").contains(capability)) return;
        if (run.projectKey() != null && java.util.Set.of("project.context.read", "project.artifact.list", "project.artifact.read", "project.artifact.write", "project.artifact.validate").contains(capability)) return;
        if (!"skill-advisor".equals(run.profileKey()) || !java.util.Set.of(
                "user.context.read", "skill.search", "skill.detail", "skill.file.read", "wiki.search", "wiki.read",
                "feishu.search", "feishu.read", "recommendation.submit").contains(capability)) {
            throw new BusinessException("AGENT_CAPABILITY_DENIED", "Agent capability is not allowed", HttpStatus.FORBIDDEN);
        }
    }
    public record IssuedRun(AgentRun run, String token) {}
}
