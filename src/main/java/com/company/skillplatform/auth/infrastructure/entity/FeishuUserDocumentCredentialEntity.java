package com.company.skillplatform.auth.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feishu_user_document_credential")
public class FeishuUserDocumentCredentialEntity extends BaseJpaEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private IamUserEntity user;
    @Column(name = "access_token_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String accessTokenCiphertext;
    @Column(name = "refresh_token_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String refreshTokenCiphertext;
    @Column(name = "access_expires_at", nullable = false)
    private Instant accessExpiresAt;
    @Column(name = "refresh_expires_at")
    private Instant refreshExpiresAt;
    @Column(name = "granted_scopes", length = 2000)
    private String grantedScopes;
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    protected FeishuUserDocumentCredentialEntity() {}
    public FeishuUserDocumentCredentialEntity(IamUserEntity user, String accessTokenCiphertext,
            String refreshTokenCiphertext, Instant accessExpiresAt, Instant refreshExpiresAt,
            String grantedScopes) {
        this.user = user;
        this.accessTokenCiphertext = accessTokenCiphertext;
        this.refreshTokenCiphertext = refreshTokenCiphertext;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshExpiresAt = refreshExpiresAt;
        this.grantedScopes = grantedScopes;
        this.status = "AUTHORIZED";
    }
    public IamUserEntity getUser() { return user; }
    public String getAccessTokenCiphertext() { return accessTokenCiphertext; }
    public String getRefreshTokenCiphertext() { return refreshTokenCiphertext; }
    public Instant getAccessExpiresAt() { return accessExpiresAt; }
    public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
    public String getGrantedScopes() { return grantedScopes; }
    public String getStatus() { return status; }
    public boolean isAuthorized() { return "AUTHORIZED".equals(status); }
    public boolean isAccessValidAt(Instant now, java.time.Duration safetyWindow) {
        return isAuthorized() && accessExpiresAt.isAfter(now.plus(safetyWindow));
    }
    public void update(String accessTokenCiphertext, String refreshTokenCiphertext, Instant accessExpiresAt,
            Instant refreshExpiresAt, String grantedScopes) {
        this.accessTokenCiphertext = accessTokenCiphertext;
        this.refreshTokenCiphertext = refreshTokenCiphertext;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshExpiresAt = refreshExpiresAt;
        this.grantedScopes = grantedScopes;
        this.status = "AUTHORIZED";
    }
    public void requireReauthorization() { this.status = "REAUTH_REQUIRED"; }
}
