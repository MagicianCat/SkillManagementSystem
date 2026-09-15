package com.company.skillplatform.auth.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "auth_browser_session")
public class BrowserSessionEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private IamUserEntity user;
    @Column(name = "session_hash", nullable = false, columnDefinition = "char(64)", unique = true)
    private String sessionHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "client_info", length = 512)
    private String clientInfo;

    protected BrowserSessionEntity() {}
    public BrowserSessionEntity(IamUserEntity user, String sessionHash, Instant expiresAt, Instant now, String clientInfo) {
        this.user = user; this.sessionHash = sessionHash; this.expiresAt = expiresAt;
        this.lastSeenAt = now; this.clientInfo = clientInfo;
    }
    public IamUserEntity getUser() { return user; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public boolean isUsableAt(Instant now) { return revokedAt == null && expiresAt.isAfter(now) && user.getStatus().name().equals("ACTIVE"); }
    public void touch(Instant now) { lastSeenAt = now; }
    public void revoke(Instant now) { revokedAt = now; }
}
