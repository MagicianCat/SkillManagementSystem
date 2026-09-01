package com.company.skillplatform.auth.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "auth_refresh_token")
public class AuthRefreshTokenEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private IamUserEntity user;
    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "client_info", length = 512)
    private String clientInfo;

    protected AuthRefreshTokenEntity() {}
    public AuthRefreshTokenEntity(IamUserEntity user, String tokenHash, Instant expiresAt, String clientInfo) {
        this.user = user; this.tokenHash = tokenHash; this.expiresAt = expiresAt; this.clientInfo = clientInfo;
    }
    public IamUserEntity getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getClientInfo() { return clientInfo; }
    public boolean isUsableAt(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    public void revoke(Instant now) { revokedAt = now; }
}
