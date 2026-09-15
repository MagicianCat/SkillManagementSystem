package com.company.skillplatform.auth.application;

import com.company.skillplatform.auth.domain.AuthenticatedUser;
import com.company.skillplatform.auth.infrastructure.JwtTokenService;
import com.company.skillplatform.auth.infrastructure.entity.BrowserSessionEntity;
import com.company.skillplatform.auth.infrastructure.repository.BrowserSessionRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrowserSessionService {
    private final BrowserSessionRepository sessions;
    private final IamUserRepository users;
    private final AuthService auth;
    private final JwtTokenService jwt;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    public BrowserSessionService(BrowserSessionRepository sessions, IamUserRepository users, AuthService auth, JwtTokenService jwt) {
        this.sessions = sessions; this.users = users; this.auth = auth; this.jwt = jwt;
    }

    @Transactional
    public Issued issue(Long userId, String clientInfo) {
        var user = users.findById(userId).orElseThrow(() -> invalid());
        String raw = newToken(); Instant now = clock.instant();
        sessions.save(new BrowserSessionEntity(user, AuthService.sha256(raw), jwt.refreshExpiresAt(), now, trim(clientInfo)));
        AuthenticatedUser principal = auth.currentUser(userId);
        return new Issued(raw, jwt.createAccessToken(principal), jwt.accessExpiresInSeconds(), principal);
    }

    @Transactional
    public Issued restore(String raw) {
        if (raw == null || raw.isBlank()) throw invalid();
        var session = sessions.findBySessionHashForUpdate(AuthService.sha256(raw)).orElseThrow(this::invalid);
        Instant now = clock.instant();
        if (!session.isUsableAt(now)) throw invalid();
        session.touch(now); sessions.save(session);
        AuthenticatedUser user = auth.currentUser(session.getUser().getId());
        return new Issued(null, jwt.createAccessToken(user), jwt.accessExpiresInSeconds(), user);
    }

    @Transactional
    public void revoke(String raw) {
        if (raw == null || raw.isBlank()) return;
        sessions.findBySessionHashForUpdate(AuthService.sha256(raw)).ifPresent(s -> s.revoke(clock.instant()));
    }

    private String newToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String trim(String value) { return value == null ? null : value.substring(0, Math.min(value.length(), 512)); }
    private BusinessException invalid() { return new BusinessException("BROWSER_SESSION_INVALID", "Browser session is invalid or expired", HttpStatus.UNAUTHORIZED); }
    public record Issued(String sessionToken, String accessToken, long expiresIn, AuthenticatedUser user) {}
}
