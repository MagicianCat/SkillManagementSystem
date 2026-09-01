package com.company.skillplatform.auth.application;

import com.company.skillplatform.auth.domain.AuthenticatedUser;
import com.company.skillplatform.auth.domain.IdentityProvider;
import com.company.skillplatform.auth.infrastructure.JwtTokenService;
import com.company.skillplatform.auth.infrastructure.entity.AuthRefreshTokenEntity;
import com.company.skillplatform.auth.infrastructure.repository.AuthRefreshTokenRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamRolePermissionRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final Map<String, IdentityProvider> providers;
    private final IamUserRepository users;
    private final IamUserRoleRepository userRoles;
    private final IamRolePermissionRepository rolePermissions;
    private final AuthRefreshTokenRepository refreshTokens;
    private final JwtTokenService jwt;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public AuthService(List<IdentityProvider> providers, IamUserRepository users,
                       IamUserRoleRepository userRoles, IamRolePermissionRepository rolePermissions,
                       AuthRefreshTokenRepository refreshTokens, JwtTokenService jwt) {
        this(providers, users, userRoles, rolePermissions, refreshTokens, jwt, Clock.systemUTC(), new SecureRandom());
    }
    AuthService(List<IdentityProvider> providers, IamUserRepository users,
                IamUserRoleRepository userRoles, IamRolePermissionRepository rolePermissions,
                AuthRefreshTokenRepository refreshTokens, JwtTokenService jwt, Clock clock, SecureRandom random) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(IdentityProvider::providerKey, Function.identity()));
        this.users = users; this.userRoles = userRoles; this.rolePermissions = rolePermissions;
        this.refreshTokens = refreshTokens; this.jwt = jwt; this.clock = clock; this.random = random;
    }

    @Transactional
    public TokenResult login(String username, String password, String provider, String clientInfo) {
        IdentityProvider identityProvider = providers.get(provider.toUpperCase(Locale.ROOT));
        if (identityProvider == null) throw new BusinessException("IDENTITY_PROVIDER_UNSUPPORTED", "Unsupported identity provider", HttpStatus.BAD_REQUEST);
        IamUserEntity user = identityProvider.authenticate(username, password);
        user.recordLogin(clock.instant());
        return issue(user, clientInfo);
    }

    @Transactional
    public TokenResult refresh(String rawToken, String clientInfo) {
        AuthRefreshTokenEntity stored = refreshTokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(this::invalidRefreshToken);
        Instant now = clock.instant();
        if (!stored.isUsableAt(now) || stored.getUser().getStatus() != UserStatus.ACTIVE) throw invalidRefreshToken();
        stored.revoke(now);
        return issue(stored.getUser(), clientInfo);
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(sha256(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) token.revoke(clock.instant());
        });
    }

    @Transactional(readOnly = true)
    public AuthenticatedUser currentUser(Long userId) { return loadUser(userId); }

    private TokenResult issue(IamUserEntity user, String clientInfo) {
        AuthenticatedUser principal = loadUser(user.getId());
        String refreshToken = newRefreshToken();
        refreshTokens.save(new AuthRefreshTokenEntity(user, sha256(refreshToken), jwt.refreshExpiresAt(), trim(clientInfo)));
        return new TokenResult(jwt.createAccessToken(principal), refreshToken, jwt.accessExpiresInSeconds(), principal);
    }
    private AuthenticatedUser loadUser(Long userId) {
        IamUserEntity user = users.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(),
                List.copyOf(userRoles.findRoleKeysByUserId(userId)),
                List.copyOf(rolePermissions.findPermissionKeysByUserId(userId)));
    }
    private String newRefreshToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private String trim(String value) { return value == null ? null : value.substring(0, Math.min(value.length(), 512)); }
    private BusinessException invalidRefreshToken() {
        return new BusinessException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired", HttpStatus.UNAUTHORIZED);
    }
    public record TokenResult(String accessToken, String refreshToken, long expiresIn, AuthenticatedUser user) {}
}
