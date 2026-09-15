package com.company.skillplatform.auth.application;

import com.company.skillplatform.auth.infrastructure.FeishuProperties;
import com.company.skillplatform.auth.infrastructure.entity.FeishuUserDocumentCredentialEntity;
import com.company.skillplatform.auth.infrastructure.repository.FeishuUserDocumentCredentialRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeishuUserTokenService {
    private static final Duration SAFETY_WINDOW = Duration.ofMinutes(5);
    private final FeishuUserDocumentCredentialRepository credentials;
    private final IamUserRepository users;
    private final FeishuProperties config;
    private final FeishuTokenCipher cipher;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Clock clock = Clock.systemUTC();

    public FeishuUserTokenService(FeishuUserDocumentCredentialRepository credentials, IamUserRepository users,
            FeishuProperties config, FeishuTokenCipher cipher, ObjectMapper json) {
        this.credentials = credentials; this.users = users; this.config = config; this.cipher = cipher; this.json = json;
    }

    @Transactional
    public void saveAuthorization(Long userId, String accessToken, String refreshToken, long expiresIn,
            long refreshExpiresIn, String scopes) {
        IamUserEntity user = users.findById(userId).orElseThrow(() -> new BusinessException("AUTHENTICATION_INVALID", "Authentication is no longer valid", HttpStatus.UNAUTHORIZED));
        Instant now = clock.instant();
        FeishuUserDocumentCredentialEntity credential = credentials.findByUserIdForUpdate(userId).orElse(null);
        if (credential == null) credentials.save(new FeishuUserDocumentCredentialEntity(user, cipher.encrypt(accessToken), cipher.encrypt(refreshToken),
                now.plusSeconds(expiresIn), refreshExpiresIn > 0 ? now.plusSeconds(refreshExpiresIn) : null, scopes));
        else { credential.update(cipher.encrypt(accessToken), cipher.encrypt(refreshToken), now.plusSeconds(expiresIn), refreshExpiresIn > 0 ? now.plusSeconds(refreshExpiresIn) : null, scopes); credentials.save(credential); }
    }

    @Transactional
    public String accessTokenFor(Long userId) {
        FeishuUserDocumentCredentialEntity credential = credentials.findByUserIdForUpdate(userId).orElse(null);
        if (credential == null || !credential.isAuthorized()) return null;
        Instant now = clock.instant();
        if (credential.isAccessValidAt(now, SAFETY_WINDOW)) {
            try { return cipher.decrypt(credential.getAccessTokenCiphertext()); }
            catch (RuntimeException ex) { credential.requireReauthorization(); credentials.save(credential); return null; }
        }
        if (credential.getRefreshExpiresAt() != null && !credential.getRefreshExpiresAt().isAfter(now)) { credential.requireReauthorization(); credentials.save(credential); return null; }
        try {
            JsonNode token = json.readTree(refresh(cipher.decrypt(credential.getRefreshTokenCiphertext())));
            if (token.path("code").asInt(0) != 0) throw new IllegalStateException("refresh rejected");
            JsonNode data = token.has("data") ? token.path("data") : token;
            String access = text(data, "access_token"); String refresh = text(data, "refresh_token");
            if (access.isBlank() || refresh.isBlank()) throw new IllegalStateException("refresh response incomplete");
            credential.update(cipher.encrypt(access), cipher.encrypt(refresh), now.plusSeconds(data.path("expires_in").asLong(7200)),
                    data.path("refresh_expires_in").asLong(0) > 0 ? now.plusSeconds(data.path("refresh_expires_in").asLong()) : credential.getRefreshExpiresAt(), credential.getGrantedScopes());
            credentials.save(credential); return access;
        } catch (Exception ex) { credential.requireReauthorization(); credentials.save(credential); return null; }
    }

    @Transactional(readOnly = true)
    public String accessStatus(Long userId) { return credentials.findByUserId(userId).map(FeishuUserDocumentCredentialEntity::getStatus).orElse("NOT_AVAILABLE"); }

    private String refresh(String refreshToken) throws Exception {
        String body = json.writeValueAsString(Map.of("grant_type", "refresh_token", "client_id", config.appId(), "client_secret", config.appSecret(), "refresh_token", refreshToken));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(config.tokenUrl())).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Feishu token refresh HTTP " + response.statusCode());
        return response.body();
    }
    private static String text(JsonNode node, String name) { return node.path(name).asText(""); }
}
