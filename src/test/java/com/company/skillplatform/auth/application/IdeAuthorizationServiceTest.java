package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.infrastructure.entity.IdeAuthorizationEntity;
import com.company.skillplatform.auth.infrastructure.repository.IdeAuthorizationRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IdeAuthorizationServiceTest {
    private final IdeAuthorizationRepository authorizations = mock(IdeAuthorizationRepository.class);
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final AuthService auth = mock(AuthService.class);
    private final Instant now = Instant.parse("2026-09-10T08:00:00Z");
    private IdeAuthorizationService service;
    private IamUserEntity user;

    @BeforeEach
    void setUp() {
        service = new IdeAuthorizationService(authorizations, users, auth,
                Clock.fixed(now, ZoneOffset.UTC), new SecureRandom(new byte[]{1, 2, 3}),
                "http://localhost:5173/ide/authorize");
        user = new IamUserEntity(IdentityProviderType.FEISHU, "ou_1", "user", "User", null);
        ReflectionTestUtils.setField(user, "id", 7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(authorizations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsFiveMinuteS256AuthorizationWithoutPersistingRawCodes() {
        var result = service.create("CodeBuddy", challenge("verifier"), "S256");

        assertThat(result.expiresIn()).isEqualTo(300);
        assertThat(result.pollInterval()).isEqualTo(5);
        assertThat(result.verificationUriComplete()).contains("user_code=");
        var captor = org.mockito.ArgumentCaptor.forClass(IdeAuthorizationEntity.class);
        verify(authorizations).save(captor.capture());
        assertThat(captor.getValue().getDeviceCodeHash()).isEqualTo(AuthService.sha256(result.deviceCode()));
        assertThat(captor.getValue().getUserCodeHash()).isEqualTo(AuthService.sha256(normalize(result.userCode())));
        assertThat(captor.getValue().getPkceChallenge()).isEqualTo(challenge("verifier"));
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(now.plusSeconds(300));
    }

    @Test
    void approvesAndExchangesExactlyOnceWithPkce() {
        IdeAuthorizationEntity entity = pending("device", "ABCD-EFGH", challenge("verifier"), now.plusSeconds(60));
        when(authorizations.findByUserCodeHashForUpdate(AuthService.sha256("ABCDEFGH"))).thenReturn(Optional.of(entity));
        service.approve("abcd-efgh", 7L);
        when(authorizations.findByDeviceCodeHashForUpdate(AuthService.sha256("device"))).thenReturn(Optional.of(entity));
        when(auth.issueForUser(user, "CodeBuddy IDE")).thenReturn(new AuthService.TokenResult("access", "refresh", 1800, null));

        var result = service.exchange("device", "verifier", "CodeBuddy IDE");

        assertThat(result.status()).isEqualTo(IdeAuthorizationService.ExchangeStatus.AUTHORIZED);
        assertThat(entity.getStatus()).isEqualTo("CONSUMED");
        assertThatThrownBy(() -> service.exchange("device", "verifier", "CodeBuddy IDE"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("IDE_AUTHORIZATION_REPLAYED"));
    }

    @Test
    void returnsPendingWithoutIssuingTokens() {
        IdeAuthorizationEntity entity = pending("device", "ABCD-EFGH", challenge("verifier"), now.plusSeconds(60));
        when(authorizations.findByDeviceCodeHashForUpdate(any())).thenReturn(Optional.of(entity));
        assertThat(service.exchange("device", "verifier", null).status())
                .isEqualTo(IdeAuthorizationService.ExchangeStatus.PENDING);
    }

    @Test
    void rejectsExpiredDeniedAndPkceMismatchWithStableCodes() {
        assertCode(expired(), "IDE_AUTHORIZATION_EXPIRED");
        IdeAuthorizationEntity denied = pending("device", "ABCD-EFGH", challenge("verifier"), now.plusSeconds(60));
        denied.deny();
        assertCode(denied, "IDE_AUTHORIZATION_DENIED");
        assertCode(pending("device", "ABCD-EFGH", challenge("other"), now.plusSeconds(60)),
                "IDE_AUTHORIZATION_PKCE_MISMATCH");
    }

    @Test
    void returnsTrustedStatusWithoutSecrets() {
        IdeAuthorizationEntity entity = pending("device", "ABCD-EFGH", challenge("verifier"), now.plusSeconds(60));
        when(authorizations.findByUserCodeHash(AuthService.sha256("ABCDEFGH"))).thenReturn(Optional.of(entity));

        var view = service.get("ab cd-efgh");

        assertThat(view.userCode()).isEqualTo("ABCD-EFGH");
        assertThat(view.clientName()).isEqualTo("CodeBuddy");
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.expiresAt()).isEqualTo(now.plusSeconds(60));
        assertThat(view.toString()).doesNotContain("device", challenge("verifier"));
    }

    @Test
    void cleanupDeletesExpiredAndOldTerminalRows() {
        service.cleanup();
        verify(authorizations).deleteExpiredActive(now);
        verify(authorizations).deleteOldTerminal(now.minus(java.time.Duration.ofDays(1)));
    }

    private void assertCode(IdeAuthorizationEntity entity, String code) {
        when(authorizations.findByDeviceCodeHashForUpdate(any())).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.exchange("device", "verifier", null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    private IdeAuthorizationEntity expired() {
        return pending("device", "ABCD-EFGH", challenge("verifier"), now.minusSeconds(1));
    }

    private IdeAuthorizationEntity pending(String device, String userCode, String challenge, Instant expiresAt) {
        return new IdeAuthorizationEntity(AuthService.sha256(device), AuthService.sha256(normalize(userCode)),
                challenge, "CodeBuddy", expiresAt);
    }

    private static String normalize(String value) { return value.replace("-", "").toUpperCase(); }
    private static String challenge(String verifier) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
