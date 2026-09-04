package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.skillplatform.auth.domain.IdentityProvider;
import com.company.skillplatform.auth.infrastructure.JwtTokenService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证认证日志事件名、关键字段与敏感信息脱敏。 */
class AuthServiceLoggingTest {

    private final IamUserRepository users = mock(IamUserRepository.class);
    private final IamUserRoleRepository userRoles = mock(IamUserRoleRepository.class);
    private final IamRolePermissionRepository permissions = mock(IamRolePermissionRepository.class);
    private final AuthRefreshTokenRepository tokens = mock(AuthRefreshTokenRepository.class);
    private final JwtTokenService jwt = mock(JwtTokenService.class);
    private final IdentityProvider provider = mock(IdentityProvider.class);
    private final Instant now = Instant.parse("2026-09-01T08:00:00Z");
    private AuthService service;
    private IamUserEntity user;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach void setUp() {
        when(provider.providerKey()).thenReturn("MOCK");
        service = new AuthService(List.of(provider), users, userRoles, permissions, tokens, jwt,
                Clock.fixed(now, ZoneOffset.UTC), new SecureRandom(new byte[] {1, 2, 3, 4}));
        user = new IamUserEntity(IdentityProviderType.MOCK, "maintainer", "maintainer", "hash", "Maintainer", null);
        ReflectionTestUtils.setField(user, "id", 7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(userRoles.findRoleKeysByUserId(7L)).thenReturn(List.of("MAINTAINER"));
        when(permissions.findPermissionKeysByUserId(7L)).thenReturn(List.of("skill:edit"));
        when(jwt.createAccessToken(any())).thenReturn("access");
        when(jwt.accessExpiresInSeconds()).thenReturn(1800L);
        when(jwt.refreshExpiresAt()).thenReturn(now.plusSeconds(604800));
        MDC.put("requestId", "req-test-1");
        Logger logger = (Logger) LoggerFactory.getLogger(AuthService.class);
        appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
    }

    @AfterEach void tearDown() {
        ((Logger) LoggerFactory.getLogger(AuthService.class)).detachAppender(appender);
        MDC.clear();
    }

    @Test void loginSuccessLogsEventWithActorAndDuration() {
        when(provider.authenticate("maintainer", "secret")).thenReturn(new IdentityProvider.AuthenticationResult(7L));
        service.login("maintainer", "secret", "mock", null);
        ILoggingEvent event = find("event=auth.login.success");
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String formatted = event.getFormattedMessage();
        assertThat(formatted).contains("requestId=req-test-1", "actorId=7", "provider=mock", "durationMs=");
        assertThat(formatted).doesNotContain("secret", "access", "hash");
    }

    @Test void loginFailureLogsWarnAndNeverLogsPassword() {
        when(provider.authenticate("maintainer", "wrong-password"))
                .thenThrow(new BusinessException("AUTHENTICATION_INVALID", "bad", org.springframework.http.HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.login("maintainer", "wrong-password", "mock", null))
                .isInstanceOf(BusinessException.class);
        ILoggingEvent event = find("event=auth.login.failure");
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        String formatted = event.getFormattedMessage();
        assertThat(formatted).contains("requestId=req-test-1", "errorCode=AUTHENTICATION_INVALID");
        assertThat(formatted).doesNotContain("wrong-password");
    }

    @Test void unsupportedProviderLogsFailure() {
        assertThatThrownBy(() -> service.login("a", "b", "oa", null)).isInstanceOf(BusinessException.class);
        assertThat(find("event=auth.login.failure").getFormattedMessage()).contains("errorCode=IDENTITY_PROVIDER_UNSUPPORTED");
    }

    @Test void refreshFailureLogsWarnWithoutToken() {
        when(tokens.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh("raw-refresh-token-value", null)).isInstanceOf(BusinessException.class);
        ILoggingEvent event = find("event=auth.refresh.failure");
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("errorCode=INVALID_REFRESH_TOKEN")
                .doesNotContain("raw-refresh-token-value");
    }

    private ILoggingEvent find(String marker) {
        return appender.list.stream().filter(e -> e.getFormattedMessage().startsWith(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("log event not found: " + marker + " in " + appender.list));
    }
}
