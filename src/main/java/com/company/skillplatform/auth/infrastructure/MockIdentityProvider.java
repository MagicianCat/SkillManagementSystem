package com.company.skillplatform.auth.infrastructure;

import com.company.skillplatform.auth.domain.IdentityProvider;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class MockIdentityProvider implements IdentityProvider {
    private final IamUserRepository users;
    private final PasswordEncoder encoder;
    public MockIdentityProvider(IamUserRepository users, PasswordEncoder encoder) {
        this.users = users; this.encoder = encoder;
    }
    @Override public String providerKey() { return "MOCK"; }
    @Override public IamUserEntity authenticate(String username, String password) {
        IamUserEntity user = users.findByUsername(username)
                .orElseThrow(this::badCredentials);
        if (user.getIdentityProvider() != IdentityProviderType.MOCK
                || user.getStatus() != UserStatus.ACTIVE
                || user.getPasswordHash() == null
                || !encoder.matches(password, user.getPasswordHash())) {
            throw badCredentials();
        }
        return user;
    }
    private BusinessException badCredentials() {
        return new BusinessException("INVALID_CREDENTIALS", "Invalid username or password", HttpStatus.UNAUTHORIZED);
    }
}
