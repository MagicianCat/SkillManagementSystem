package com.company.skillplatform.auth.application;

import com.company.skillplatform.auth.infrastructure.entity.IdeAuthorizationEntity;
import com.company.skillplatform.auth.infrastructure.repository.IdeAuthorizationRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class IdeAuthorizationService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int POLL_INTERVAL_SECONDS = 5;
    private final IdeAuthorizationRepository authorizations;
    private final IamUserRepository users;
    private final AuthService auth;
    private final Clock clock;
    private final SecureRandom random;
    private final String verificationUri;

    @Autowired
    public IdeAuthorizationService(IdeAuthorizationRepository authorizations, IamUserRepository users,
                                   AuthService auth,
                                   @Value("${auth.ide.verification-uri:http://localhost:5173/ide/authorize}") String verificationUri) {
        this(authorizations, users, auth, Clock.systemUTC(), new SecureRandom(), verificationUri);
    }

    IdeAuthorizationService(IdeAuthorizationRepository authorizations, IamUserRepository users, AuthService auth,
                            Clock clock, SecureRandom random, String verificationUri) {
        this.authorizations = authorizations;
        this.users = users;
        this.auth = auth;
        this.clock = clock;
        this.random = random;
        this.verificationUri = verificationUri;
    }

    @Transactional
    public AuthorizationResult create(String clientName, String codeChallenge, String codeChallengeMethod) {
        if (!"S256".equals(codeChallengeMethod)) throw error("IDE_PKCE_METHOD_UNSUPPORTED", "Only S256 PKCE is supported", HttpStatus.BAD_REQUEST);
        String deviceCode = randomToken(32);
        String userCode = userCode();
        Instant expiresAt = clock.instant().plus(TTL);
        authorizations.save(new IdeAuthorizationEntity(AuthService.sha256(deviceCode),
                AuthService.sha256(normalizeUserCode(userCode)), codeChallenge, clientName.trim(), expiresAt));
        String complete = verificationUri + (verificationUri.contains("?") ? "&" : "?") + "user_code="
                + URLEncoder.encode(userCode, StandardCharsets.UTF_8);
        return new AuthorizationResult(deviceCode, userCode, verificationUri, complete,
                TTL.toSeconds(), POLL_INTERVAL_SECONDS);
    }

    @Transactional
    public void approve(String userCode, Long userId) {
        IdeAuthorizationEntity authorization = authorizations.findByUserCodeHashForUpdate(
                        AuthService.sha256(normalizeUserCode(userCode)))
                .orElseThrow(() -> error("IDE_AUTHORIZATION_NOT_FOUND", "IDE authorization was not found", HttpStatus.NOT_FOUND));
        ensureNotExpired(authorization);
        if (!"PENDING".equals(authorization.getStatus()))
            throw error("IDE_AUTHORIZATION_NOT_PENDING", "IDE authorization is no longer pending", HttpStatus.CONFLICT);
        var user = users.findById(userId)
                .orElseThrow(() -> error("AUTHENTICATION_INVALID", "Authentication is no longer valid", HttpStatus.UNAUTHORIZED));
        authorization.approve(user, clock.instant());
    }

    @Transactional(readOnly = true)
    public AuthorizationView get(String userCode) {
        String normalized=normalizeUserCode(userCode);
        IdeAuthorizationEntity authorization=authorizations.findByUserCodeHash(AuthService.sha256(normalized))
                .orElseThrow(()->error("IDE_AUTHORIZATION_NOT_FOUND","IDE authorization was not found",HttpStatus.NOT_FOUND));
        return new AuthorizationView(formatUserCode(normalized),authorization.getClientName(),authorization.getStatus(),authorization.getExpiresAt());
    }

    @Scheduled(fixedDelayString="${auth.ide.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanup(){Instant now=clock.instant();authorizations.deleteExpiredActive(now);authorizations.deleteOldTerminal(now.minus(Duration.ofDays(1)));}

    @Transactional
    public ExchangeResult exchange(String deviceCode, String codeVerifier, String clientInfo) {
        IdeAuthorizationEntity authorization = authorizations.findByDeviceCodeHashForUpdate(AuthService.sha256(deviceCode))
                .orElseThrow(() -> error("IDE_AUTHORIZATION_INVALID", "IDE authorization is invalid", HttpStatus.BAD_REQUEST));
        ensureNotExpired(authorization);
        String actualChallenge = pkceChallenge(codeVerifier);
        if (!MessageDigest.isEqual(authorization.getPkceChallenge().getBytes(StandardCharsets.US_ASCII),
                actualChallenge.getBytes(StandardCharsets.US_ASCII)))
            throw error("IDE_AUTHORIZATION_PKCE_MISMATCH", "PKCE verification failed", HttpStatus.BAD_REQUEST);
        return switch (authorization.getStatus()) {
            case "PENDING" -> new ExchangeResult(ExchangeStatus.PENDING, null);
            case "DENIED" -> throw error("IDE_AUTHORIZATION_DENIED", "IDE authorization was denied", HttpStatus.FORBIDDEN);
            case "CONSUMED" -> throw error("IDE_AUTHORIZATION_REPLAYED", "IDE authorization was already consumed", HttpStatus.CONFLICT);
            case "APPROVED" -> {
                AuthService.TokenResult tokens = auth.issueForUser(authorization.getUser(), clientInfo);
                authorization.consume(clock.instant());
                yield new ExchangeResult(ExchangeStatus.AUTHORIZED, tokens);
            }
            default -> throw error("IDE_AUTHORIZATION_INVALID", "IDE authorization is invalid", HttpStatus.BAD_REQUEST);
        };
    }

    private void ensureNotExpired(IdeAuthorizationEntity authorization) {
        if (!authorization.getExpiresAt().isAfter(clock.instant()))
            throw error("IDE_AUTHORIZATION_EXPIRED", "IDE authorization has expired", HttpStatus.GONE);
    }
    private String randomToken(int bytes) { byte[] value = new byte[bytes]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String userCode() { String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; StringBuilder out = new StringBuilder(9); for (int i=0;i<8;i++) { if (i==4) out.append('-'); out.append(alphabet.charAt(random.nextInt(alphabet.length()))); } return out.toString(); }
    private String normalizeUserCode(String value) { return value.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT); }
    private String formatUserCode(String value){return value.substring(0,4)+"-"+value.substring(4);}
    private String pkceChallenge(String verifier) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public enum ExchangeStatus { PENDING, AUTHORIZED }
    public record AuthorizationResult(String deviceCode, String userCode, String verificationUri,
                                      String verificationUriComplete, long expiresIn, int pollInterval) {}
    public record ExchangeResult(ExchangeStatus status, AuthService.TokenResult tokens) {}
    public record AuthorizationView(String userCode,String clientName,String status,Instant expiresAt) {}
}
