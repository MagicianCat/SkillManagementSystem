package com.company.skillplatform.auth.domain;

public interface IdentityProvider {
    String providerKey();
    AuthenticationResult authenticate(String username, String password);

    record AuthenticationResult(Long userId) {}
}
