package com.company.skillplatform.user.domain;

public enum IdentityProviderType {
    FEISHU, LEGACY,
    /** Historical database value only; password authentication has been removed. */
    @Deprecated MOCK,
    /** Historical database value only; direct OA authentication has been removed. */
    @Deprecated OA
}
