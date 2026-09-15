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
@Table(name = "auth_ide_authorization")
public class IdeAuthorizationEntity extends BaseJpaEntity {
    @Column(name = "device_code_hash", nullable = false, unique = true, columnDefinition = "char(64)")
    private String deviceCodeHash;
    @Column(name = "user_code_hash", nullable = false, unique = true, columnDefinition = "char(64)")
    private String userCodeHash;
    @Column(name = "pkce_challenge", nullable = false, length = 128)
    private String pkceChallenge;
    @Column(name = "client_name", nullable = false, length = 128)
    private String clientName;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private IamUserEntity user;

    protected IdeAuthorizationEntity() {}

    public IdeAuthorizationEntity(String deviceCodeHash, String userCodeHash, String pkceChallenge,
                                  String clientName, Instant expiresAt) {
        this.deviceCodeHash = deviceCodeHash;
        this.userCodeHash = userCodeHash;
        this.pkceChallenge = pkceChallenge;
        this.clientName = clientName;
        this.expiresAt = expiresAt;
        this.status = "PENDING";
    }

    public void approve(IamUserEntity approvedUser, Instant now) {
        this.user = approvedUser;
        this.approvedAt = now;
        this.status = "APPROVED";
    }
    public void consume(Instant now) { this.consumedAt = now; this.status = "CONSUMED"; }
    public void deny() { this.status = "DENIED"; }
    public String getDeviceCodeHash() { return deviceCodeHash; }
    public String getUserCodeHash() { return userCodeHash; }
    public String getPkceChallenge() { return pkceChallenge; }
    public String getClientName() { return clientName; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public IamUserEntity getUser() { return user; }
}
