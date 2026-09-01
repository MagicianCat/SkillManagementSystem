package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.domain.IdentityProvider;
import com.company.skillplatform.auth.infrastructure.JwtTokenService;
import com.company.skillplatform.auth.infrastructure.entity.AuthRefreshTokenEntity;
import com.company.skillplatform.auth.infrastructure.repository.AuthRefreshTokenRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamRolePermissionRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final IamUserRoleRepository userRoles = mock(IamUserRoleRepository.class);
    private final IamRolePermissionRepository permissions = mock(IamRolePermissionRepository.class);
    private final AuthRefreshTokenRepository tokens = mock(AuthRefreshTokenRepository.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final IdentityProvider provider = mock(IdentityProvider.class);
    private final SecureRandom random = new SecureRandom(new byte[] {1, 2, 3, 4});
    private final Instant now = Instant.parse("2026-09-01T08:00:00Z");
    private AuthService service;
    private IamUserEntity user;

    @BeforeEach void setUp() {
        when(provider.providerKey()).thenReturn("MOCK");
        service = new AuthService(List.of(provider), users, userRoles, permissions, tokens, jwt,
                Clock.fixed(now, ZoneOffset.UTC), random);
        user = new IamUserEntity(IdentityProviderType.MOCK, "admin", "admin", "hash", "Admin", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(userRoles.findRoleKeysByUserId(1L)).thenReturn(List.of("ADMIN"));
        when(permissions.findPermissionKeysByUserId(1L)).thenReturn(List.of("admin:identity"));
        when(jwt.createAccessToken(any())).thenReturn("access");
        when(jwt.accessExpiresInSeconds()).thenReturn(1800L);
        when(jwt.refreshExpiresAt()).thenReturn(now.plusSeconds(604800));
    }

    @Test void loginIssuesAccessAndRefreshTokensAndRecordsLogin() {
        when(provider.authenticate("admin", "secret")).thenReturn(user);
        var result = service.login("admin", "secret", "mock", "x".repeat(600));
        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.expiresIn()).isEqualTo(1800);
        assertThat(result.user().permissions()).containsExactly("admin:identity");
        assertThat(user.getLastLoginAt()).isEqualTo(now);
        verify(tokens).save(any(AuthRefreshTokenEntity.class));
    }

    @Test void loginRejectsUnknownProvider() {
        assertThatThrownBy(() -> service.login("a", "b", "oa", null))
                .isInstanceOf(BusinessException.class).hasMessage("Unsupported identity provider");
    }

    @Test void refreshRotatesUsableToken() {
        AuthRefreshTokenEntity stored = new AuthRefreshTokenEntity(user, AuthService.sha256("refresh"), now.plusSeconds(30), null);
        when(tokens.findByTokenHash(AuthService.sha256("refresh"))).thenReturn(Optional.of(stored));
        var result = service.refresh("refresh", null);
        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(stored.getRevokedAt()).isEqualTo(now);
    }

    @Test void refreshRejectsMissingExpiredAndDisabledTokens() {
        when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh("missing", null)).isInstanceOf(BusinessException.class);
        AuthRefreshTokenEntity expired = new AuthRefreshTokenEntity(user, "x", now.minusSeconds(1), null);
        when(tokens.findByTokenHash(any())).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.refresh("expired", null)).isInstanceOf(BusinessException.class);
        user.changeStatus(com.company.skillplatform.user.domain.UserStatus.DISABLED);
        AuthRefreshTokenEntity active = new AuthRefreshTokenEntity(user, "x", now.plusSeconds(30), null);
        when(tokens.findByTokenHash(any())).thenReturn(Optional.of(active));
        assertThatThrownBy(() -> service.refresh("disabled", null)).isInstanceOf(BusinessException.class);
    }

    @Test void logoutRevokesOnlyKnownActiveToken() {
        AuthRefreshTokenEntity stored = new AuthRefreshTokenEntity(user, "x", now.plusSeconds(30), null);
        when(tokens.findByTokenHash(any())).thenReturn(Optional.of(stored));
        service.logout("refresh"); service.logout("refresh");
        assertThat(stored.getRevokedAt()).isEqualTo(now);
        when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
        service.logout("unknown");
        verify(tokens, never()).delete(any());
    }

    @Test void currentUserLoadsAuthoritiesAndReportsMissingUser() {
        assertThat(service.currentUser(1L).roles()).containsExactly("ADMIN");
        when(users.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.currentUser(2L)).isInstanceOf(BusinessException.class);
        assertThat(AuthService.sha256("abc")).hasSize(64);
    }
}
