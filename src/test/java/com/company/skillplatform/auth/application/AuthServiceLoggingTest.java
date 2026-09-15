package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.skillplatform.auth.infrastructure.JwtTokenService;
import com.company.skillplatform.auth.infrastructure.repository.AuthRefreshTokenRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.infrastructure.repository.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AuthServiceLoggingTest {
    @Test void refreshFailureDoesNotLogRawToken() {
        var tokens=mock(AuthRefreshTokenRepository.class);
        when(tokens.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());
        var service=new AuthService(mock(IamUserRepository.class),mock(IamUserRoleRepository.class),
                mock(IamRoleRepository.class),mock(IamRolePermissionRepository.class),tokens,mock(JwtTokenService.class),
                Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"),ZoneOffset.UTC),new SecureRandom());
        Logger logger=(Logger)LoggerFactory.getLogger(AuthService.class);
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            assertThatThrownBy(()->service.refresh("raw-refresh-token-value",null)).isInstanceOf(BusinessException.class);
            String message=appender.list.get(appender.list.size()-1).getFormattedMessage();
            assertThat(message).contains("errorCode=INVALID_REFRESH_TOKEN").doesNotContain("raw-refresh-token-value");
        } finally { logger.detachAppender(appender); }
    }
}
