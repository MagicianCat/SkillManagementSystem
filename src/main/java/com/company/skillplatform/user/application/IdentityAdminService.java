package com.company.skillplatform.user.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.IamPermissionEntity;
import com.company.skillplatform.user.infrastructure.entity.IamRoleEntity;
import com.company.skillplatform.user.infrastructure.entity.IamRolePermissionEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserRoleEntity;
import com.company.skillplatform.user.infrastructure.repository.IamPermissionRepository;
import com.company.skillplatform.user.infrastructure.repository.IamRolePermissionRepository;
import com.company.skillplatform.user.infrastructure.repository.IamRoleRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityAdminService {
    private final IamUserRepository users;
    private final IamRoleRepository roles;
    private final IamPermissionRepository permissions;
    private final IamUserRoleRepository userRoles;
    private final IamRolePermissionRepository rolePermissions;
    private final PasswordEncoder encoder;
    public IdentityAdminService(IamUserRepository users, IamRoleRepository roles, IamPermissionRepository permissions,
                                IamUserRoleRepository userRoles, IamRolePermissionRepository rolePermissions,
                                PasswordEncoder encoder) {
        this.users = users; this.roles = roles; this.permissions = permissions; this.userRoles = userRoles;
        this.rolePermissions = rolePermissions; this.encoder = encoder;
    }
    @Transactional(readOnly = true) public Page<IamUserEntity> users(Pageable pageable) { return users.findAll(pageable); }
    @Transactional(readOnly = true) public List<IamRoleEntity> roles() { return roles.findAll(); }
    @Transactional
    public IamUserEntity createUser(String username, String password, String displayName, String email) {
        if (users.existsByUsername(username)) throw new BusinessException("USERNAME_EXISTS", "Username already exists", HttpStatus.CONFLICT);
        return users.save(new IamUserEntity(IdentityProviderType.MOCK, username, username, encoder.encode(password), displayName, email));
    }
    @Transactional
    public IamUserEntity changeStatus(Long userId, UserStatus status, int versionNo) {
        IamUserEntity user = user(userId);
        checkVersion(user.getVersionNo(), versionNo);
        user.changeStatus(status);
        return user;
    }
    @Transactional
    public IamRoleEntity createRole(String key, String name, String description) {
        if (roles.findByRoleKey(key).isPresent()) throw new BusinessException("ROLE_KEY_EXISTS", "Role key already exists", HttpStatus.CONFLICT);
        return roles.save(new IamRoleEntity(key, name, description));
    }
    @Transactional
    public void replaceUserRoles(Long userId, Collection<Long> roleIds, Long operatorId) {
        IamUserEntity user = user(userId); IamUserEntity operator = user(operatorId);
        List<IamRoleEntity> selected = roles.findAllByIdIn(roleIds);
        if (selected.size() != roleIds.stream().distinct().count()) throw notFound("ROLE_NOT_FOUND", "One or more roles not found");
        userRoles.deleteAllByUserId(userId);
        userRoles.saveAll(selected.stream().map(role -> new IamUserRoleEntity(user, role, operator)).toList());
    }
    @Transactional
    public void replaceRolePermissions(Long roleId, Collection<Long> permissionIds, Long operatorId) {
        IamRoleEntity role = roles.findById(roleId).orElseThrow(() -> notFound("ROLE_NOT_FOUND", "Role not found"));
        IamUserEntity operator = user(operatorId);
        List<IamPermissionEntity> selected = permissions.findAllByIdIn(permissionIds);
        if (selected.size() != permissionIds.stream().distinct().count()) throw notFound("PERMISSION_NOT_FOUND", "One or more permissions not found");
        rolePermissions.deleteAllByRoleId(roleId);
        rolePermissions.saveAll(selected.stream().map(permission -> new IamRolePermissionEntity(role, permission, operator)).toList());
    }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> notFound("USER_NOT_FOUND", "User not found")); }
    private void checkVersion(int actual, int expected) {
        if (actual != expected) throw new BusinessException("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", HttpStatus.CONFLICT);
    }
    private BusinessException notFound(String code, String message) { return new BusinessException(code, message, HttpStatus.NOT_FOUND); }
}
