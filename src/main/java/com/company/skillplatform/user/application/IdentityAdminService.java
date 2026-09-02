package com.company.skillplatform.user.application;

import com.company.skillplatform.audit.application.AuditService;
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
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAuthority('admin:identity')")
public class IdentityAdminService {
    private final IamUserRepository users;
    private final IamRoleRepository roles;
    private final IamPermissionRepository permissions;
    private final IamUserRoleRepository userRoles;
    private final IamRolePermissionRepository rolePermissions;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    public IdentityAdminService(IamUserRepository users, IamRoleRepository roles, IamPermissionRepository permissions,
                                IamUserRoleRepository userRoles, IamRolePermissionRepository rolePermissions,
                                PasswordEncoder encoder, AuditService audit) {
        this.users = users; this.roles = roles; this.permissions = permissions; this.userRoles = userRoles;
        this.rolePermissions = rolePermissions; this.encoder = encoder; this.audit = audit;
    }
    @Transactional(readOnly = true) public Page<IamUserEntity> users(Pageable pageable) { return users.findAll(pageable); }
    @Transactional(readOnly = true) public List<IamRoleEntity> roles() { return roles.findAll(); }
    @Transactional
    public IamUserEntity createUser(String username, String password, String displayName, String email,
                                    Long operatorId, String requestId) {
        if (users.existsByUsername(username)) throw new BusinessException("USERNAME_EXISTS", "Username already exists", HttpStatus.CONFLICT);
        IamUserEntity created = users.save(new IamUserEntity(IdentityProviderType.MOCK, username, username,
                encoder.encode(password), displayName, email));
        audit.success("IDENTITY_USER_CREATED", user(operatorId), "IAM_USER", created.getId(), requestId,
                null, Map.of("username", created.getUsername(), "status", created.getStatus().name()), Map.of());
        return created;
    }
    @Transactional
    public IamUserEntity changeStatus(Long userId, UserStatus status, int versionNo, Long operatorId, String requestId) {
        IamUserEntity user = user(userId);
        checkVersion(user.getVersionNo(), versionNo);
        UserStatus previous = user.getStatus();
        user.changeStatus(status);
        audit.success("IDENTITY_USER_STATUS_CHANGED", user(operatorId), "IAM_USER", userId, requestId,
                Map.of("status", previous.name()), Map.of("status", status.name()), Map.of());
        return user;
    }
    @Transactional
    public IamRoleEntity createRole(String key, String name, String description, Long operatorId, String requestId) {
        if (roles.findByRoleKey(key).isPresent()) throw new BusinessException("ROLE_KEY_EXISTS", "Role key already exists", HttpStatus.CONFLICT);
        IamRoleEntity created = roles.save(new IamRoleEntity(key, name, description));
        audit.success("IDENTITY_ROLE_CREATED", user(operatorId), "IAM_ROLE", created.getId(), requestId,
                null, Map.of("roleKey", created.getRoleKey(), "status", created.getStatus().name()), Map.of());
        return created;
    }
    @Transactional
    public void replaceUserRoles(Long userId, Collection<Long> roleIds, int versionNo, Long operatorId, String requestId) {
        IamUserEntity user = user(userId); IamUserEntity operator = user(operatorId);
        List<String> previous = userRoles.findRoleKeysByUserId(userId);
        List<IamRoleEntity> selected = roles.findAllByIdIn(roleIds);
        if (selected.size() != roleIds.stream().distinct().count()) throw notFound("ROLE_NOT_FOUND", "One or more roles not found");
        if (users.incrementVersion(userId, versionNo) != 1) throw optimisticConflict();
        userRoles.deleteAllByUserId(userId);
        userRoles.saveAll(selected.stream().map(role -> new IamUserRoleEntity(user, role, operator)).toList());
        audit.success("IDENTITY_USER_ROLES_REPLACED", operator, "IAM_USER", userId, requestId,
                Map.of("roles", previous), Map.of("roles", selected.stream().map(IamRoleEntity::getRoleKey).toList()), Map.of());
    }
    @Transactional
    public void replaceRolePermissions(Long roleId, Collection<Long> permissionIds, int versionNo,
                                       Long operatorId, String requestId) {
        IamRoleEntity role = roles.findById(roleId).orElseThrow(() -> notFound("ROLE_NOT_FOUND", "Role not found"));
        IamUserEntity operator = user(operatorId);
        List<String> previous = rolePermissions.findPermissionKeysByRoleId(roleId);
        List<IamPermissionEntity> selected = permissions.findAllByIdIn(permissionIds);
        if (selected.size() != permissionIds.stream().distinct().count()) throw notFound("PERMISSION_NOT_FOUND", "One or more permissions not found");
        if (roles.incrementVersion(roleId, versionNo) != 1) throw optimisticConflict();
        rolePermissions.deleteAllByRoleId(roleId);
        rolePermissions.saveAll(selected.stream().map(permission -> new IamRolePermissionEntity(role, permission, operator)).toList());
        audit.success("IDENTITY_ROLE_PERMISSIONS_REPLACED", operator, "IAM_ROLE", roleId, requestId,
                Map.of("permissions", previous),
                Map.of("permissions", selected.stream().map(IamPermissionEntity::getPermissionKey).toList()), Map.of());
    }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> notFound("USER_NOT_FOUND", "User not found")); }
    private void checkVersion(int actual, int expected) {
        if (actual != expected) throw optimisticConflict();
    }
    private BusinessException optimisticConflict() {
        return new BusinessException("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", HttpStatus.CONFLICT);
    }
    private BusinessException notFound(String code, String message) { return new BusinessException(code, message, HttpStatus.NOT_FOUND); }
}
