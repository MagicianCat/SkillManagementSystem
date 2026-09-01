package com.company.skillplatform.auth.domain;

import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;

public interface IdentityProvider {
    String providerKey();
    IamUserEntity authenticate(String username, String password);
}
