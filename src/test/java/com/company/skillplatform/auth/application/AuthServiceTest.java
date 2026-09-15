package com.company.skillplatform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.skillplatform.auth.infrastructure.JwtTokenService;
import com.company.skillplatform.auth.infrastructure.entity.AuthRefreshTokenEntity;
import com.company.skillplatform.auth.infrastructure.repository.AuthRefreshTokenRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.infrastructure.entity.IamRoleEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamRolePermissionRepository;
import com.company.skillplatform.user.infrastructure.repository.IamRoleRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {
    private final IamUserRepository users=mock(IamUserRepository.class); private final IamUserRoleRepository userRoles=mock(IamUserRoleRepository.class);
    private final IamRoleRepository roles=mock(IamRoleRepository.class); private final IamRolePermissionRepository permissions=mock(IamRolePermissionRepository.class);
    private final AuthRefreshTokenRepository tokens=mock(AuthRefreshTokenRepository.class); private final JwtTokenService jwt=mock(JwtTokenService.class);
    private final Instant now=Instant.parse("2026-09-01T08:00:00Z"); private AuthService service; private IamUserEntity user;
    @BeforeEach void setUp(){service=new AuthService(users,userRoles,roles,permissions,tokens,jwt,Clock.fixed(now,ZoneOffset.UTC),new SecureRandom());user=new IamUserEntity(IdentityProviderType.FEISHU,"ou_1","feishu_ou_1","User",null);ReflectionTestUtils.setField(user,"id",1L);when(users.findById(1L)).thenReturn(Optional.of(user));when(userRoles.findRoleKeysByUserId(1L)).thenReturn(List.of("CONSUMER"));when(permissions.findPermissionKeysByUserId(1L)).thenReturn(List.of("skill:browse"));when(jwt.createAccessToken(any())).thenReturn("access");when(jwt.accessExpiresInSeconds()).thenReturn(1800L);when(jwt.refreshExpiresAt()).thenReturn(now.plusSeconds(3600));}
    @Test void feishuLoginCreatesUserAndIssuesTokens(){when(users.findByFeishuOpenId("ou_1")).thenReturn(Optional.empty());when(users.findByFeishuUserId(null)).thenReturn(Optional.empty());when(users.findByIdentityProviderAndExternalUserId(IdentityProviderType.FEISHU,"ou_1")).thenReturn(Optional.empty());when(users.save(any(IamUserEntity.class))).thenAnswer(i->{IamUserEntity u=i.getArgument(0);ReflectionTestUtils.setField(u,"id",1L);return u;});when(roles.findByRoleKey("CONSUMER")).thenReturn(Optional.of(new IamRoleEntity("CONSUMER","普通用户",null)));var result=service.loginFeishu("ou_1","feishu_ou_1","User",null,null);assertThat(result.accessToken()).isEqualTo("access");assertThat(result.user().permissions()).contains("skill:browse");}
    @Test void refreshRotatesUsableToken(){AuthRefreshTokenEntity stored=new AuthRefreshTokenEntity(user,AuthService.sha256("refresh"),now.plusSeconds(30),null);when(tokens.findByTokenHashForUpdate(AuthService.sha256("refresh"))).thenReturn(Optional.of(stored));assertThat(service.refresh("refresh",null).accessToken()).isEqualTo("access");assertThat(stored.getRevokedAt()).isEqualTo(now);}
    @Test void refreshRejectsMissingToken(){when(tokens.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());assertThatThrownBy(()->service.refresh("missing",null)).isInstanceOf(BusinessException.class);}
}
