package com.company.skillplatform.user.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "iam_user")
public class IamUserEntity extends BaseJpaEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "identity_provider", nullable = false, length = 32)
    private IdentityProviderType identityProvider;
    @Column(name = "external_user_id", nullable = false, length = 128)
    private String externalUserId;
    @Column(nullable = false, length = 128)
    private String username;
    @Column(name = "password_hash", length = 255)
    private String passwordHash;
    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;
    @Column(length = 255)
    private String email;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Version
    @Column(name = "version_no", nullable = false)
    private int versionNo;

    protected IamUserEntity() {}
    public IamUserEntity(IdentityProviderType provider, String externalUserId, String username,
                         String passwordHash, String displayName, String email) {
        this.identityProvider = provider;
        this.externalUserId = externalUserId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.email = email;
        this.status = UserStatus.ACTIVE;
    }
    public IdentityProviderType getIdentityProvider() { return identityProvider; }
    public String getExternalUserId() { return externalUserId; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public UserStatus getStatus() { return status; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public int getVersionNo() { return versionNo; }
    public void recordLogin(Instant time) { lastLoginAt = time; }
    public void changeStatus(UserStatus value) { status = value; }
}
