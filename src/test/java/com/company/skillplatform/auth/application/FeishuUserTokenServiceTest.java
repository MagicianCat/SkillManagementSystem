package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.auth.infrastructure.entity.FeishuUserDocumentCredentialEntity;
import com.company.skillplatform.auth.infrastructure.repository.FeishuUserDocumentCredentialRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeishuUserTokenServiceTest {
    private final FeishuUserDocumentCredentialRepository credentials = mock(FeishuUserDocumentCredentialRepository.class);
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final FeishuTokenCipher cipher = mock(FeishuTokenCipher.class);
    private FeishuUserTokenService service;

    @BeforeEach
    void setUp() {
        FeishuProperties config = new FeishuProperties(true, "app", "secret", "callback", false, "", "authorize",
                "token", "user", "api", false, Duration.ofMinutes(5));
        service = new FeishuUserTokenService(credentials, users, config, cipher, new ObjectMapper());
    }

    @Test
    void missingCredentialDegradesToSkillOnly() {
        when(credentials.findByUserIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThat(service.accessTokenFor(7L)).isNull();
        assertThat(service.accessStatus(7L)).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void validAccessTokenIsReusedWithoutCallingRefreshEndpoint() {
        IamUserEntity user = mock(IamUserEntity.class);
        FeishuUserDocumentCredentialEntity credential = new FeishuUserDocumentCredentialEntity(user,
                "encrypted-access", "encrypted-refresh", Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(86400), "offline_access");
        when(credentials.findByUserIdForUpdate(7L)).thenReturn(Optional.of(credential));
        when(cipher.decrypt("encrypted-access")).thenReturn("access-token");

        assertThat(service.accessTokenFor(7L)).isEqualTo("access-token");
        assertThat(credential.getStatus()).isEqualTo("AUTHORIZED");
    }
}
