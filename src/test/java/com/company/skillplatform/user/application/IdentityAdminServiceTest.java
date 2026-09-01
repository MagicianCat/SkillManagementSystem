package com.company.skillplatform.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamPermissionEntity;
import com.company.skillplatform.user.infrastructure.entity.IamRoleEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamPermissionRepository;
import com.company.skillplatform.user.infrastructure.repository.IamRolePermissionRepository;
import com.company.skillplatform.user.infrastructure.repository.IamRoleRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class IdentityAdminServiceTest {
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final IamRoleRepository roles = mock(IamRoleRepository.class);
    private final IamPermissionRepository permissions = mock(IamPermissionRepository.class);
    private final IamUserRoleRepository userRoles = mock(IamUserRoleRepository.class);
    private final IamRolePermissionRepository rolePermissions = mock(IamRolePermissionRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private IdentityAdminService service;
    private IamUserEntity user;

    @BeforeEach void setUp() {
        service = new IdentityAdminService(users, roles, permissions, userRoles, rolePermissions, encoder);
        user = new IamUserEntity(IdentityProviderType.MOCK, "u", "u", "hash", "User", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test void listsAndCreatesUsers() {
        when(users.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));
        assertThat(service.users(Pageable.unpaged())).containsExactly(user);
        when(encoder.encode("pw")).thenReturn("encoded");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.createUser("new", "pw", "New", "n@x.test").getPasswordHash()).isEqualTo("encoded");
        when(users.existsByUsername("new")).thenReturn(true);
        assertThatThrownBy(() -> service.createUser("new", "pw", "New", null)).isInstanceOf(BusinessException.class);
    }

    @Test void changesStatusWithOptimisticCheck() {
        assertThat(service.changeStatus(1L, UserStatus.DISABLED, 0).getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThatThrownBy(() -> service.changeStatus(1L, UserStatus.ACTIVE, 3)).isInstanceOf(BusinessException.class);
        when(users.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeStatus(2L, UserStatus.ACTIVE, 0)).isInstanceOf(BusinessException.class);
    }

    @Test void listsAndCreatesRolesWithConflictCheck() {
        IamRoleEntity role = new IamRoleEntity("R", "Role", null);
        when(roles.findAll()).thenReturn(List.of(role));
        assertThat(service.roles()).containsExactly(role);
        when(roles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.createRole("N", "New", "desc").getRoleKey()).isEqualTo("N");
        when(roles.findByRoleKey("N")).thenReturn(Optional.of(role));
        assertThatThrownBy(() -> service.createRole("N", "New", null)).isInstanceOf(BusinessException.class);
    }

    @Test void replacesUserRolesAndValidatesIds() {
        IamRoleEntity role = new IamRoleEntity("R", "Role", null);
        when(roles.findAllByIdIn(List.of(2L))).thenReturn(List.of(role));
        service.replaceUserRoles(1L, List.of(2L), 1L);
        verify(userRoles).deleteAllByUserId(1L);
        verify(userRoles).saveAll(any());
        when(roles.findAllByIdIn(List.of(2L, 3L))).thenReturn(List.of(role));
        assertThatThrownBy(() -> service.replaceUserRoles(1L, List.of(2L, 3L), 1L)).isInstanceOf(BusinessException.class);
    }

    @Test void replacesRolePermissionsAndValidatesRolePermissionIds() {
        IamRoleEntity role = new IamRoleEntity("R", "Role", null);
        IamPermissionEntity permission = new IamPermissionEntity("p", "P", null);
        when(roles.findById(2L)).thenReturn(Optional.of(role));
        when(permissions.findAllByIdIn(List.of(3L))).thenReturn(List.of(permission));
        service.replaceRolePermissions(2L, List.of(3L), 1L);
        verify(rolePermissions).deleteAllByRoleId(2L);
        verify(rolePermissions).saveAll(any());
        when(permissions.findAllByIdIn(List.of(3L, 4L))).thenReturn(List.of(permission));
        assertThatThrownBy(() -> service.replaceRolePermissions(2L, List.of(3L, 4L), 1L)).isInstanceOf(BusinessException.class);
        when(roles.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replaceRolePermissions(9L, List.of(3L), 1L)).isInstanceOf(BusinessException.class);
    }
}
