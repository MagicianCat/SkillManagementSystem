package com.company.skillplatform.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class MockIdentityProviderTest {
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final MockIdentityProvider provider = new MockIdentityProvider(users, encoder);

    @Test void authenticatesActiveMockUser() {
        IamUserEntity user = user(IdentityProviderType.MOCK, UserStatus.ACTIVE, "hash");
        when(users.findByUsername("user")).thenReturn(Optional.of(user));
        when(encoder.matches("password", "hash")).thenReturn(true);
        assertThat(provider.providerKey()).isEqualTo("MOCK");
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 7L);
        assertThat(provider.authenticate("user", "password").userId()).isEqualTo(7L);
    }

    @Test void rejectsMissingWrongProviderInactiveMissingPasswordAndMismatch() {
        when(users.findByUsername("user")).thenReturn(Optional.empty());
        assertBad();
        when(users.findByUsername("user")).thenReturn(Optional.of(user(IdentityProviderType.OA, UserStatus.ACTIVE, "x"))); assertBad();
        when(users.findByUsername("user")).thenReturn(Optional.of(user(IdentityProviderType.MOCK, UserStatus.LOCKED, "x"))); assertBad();
        when(users.findByUsername("user")).thenReturn(Optional.of(user(IdentityProviderType.MOCK, UserStatus.ACTIVE, null))); assertBad();
        when(users.findByUsername("user")).thenReturn(Optional.of(user(IdentityProviderType.MOCK, UserStatus.ACTIVE, "x"))); assertBad();
    }
    private void assertBad() { assertThatThrownBy(() -> provider.authenticate("user", "bad")).isInstanceOf(BusinessException.class); }
    private IamUserEntity user(IdentityProviderType type, UserStatus status, String hash) {
        IamUserEntity user = new IamUserEntity(type, "id", "user", hash, "User", null); user.changeStatus(status); return user;
    }
}
