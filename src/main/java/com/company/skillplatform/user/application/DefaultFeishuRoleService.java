package com.company.skillplatform.user.application;

import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import com.company.skillplatform.user.infrastructure.repository.IamRoleRepository;
import com.company.skillplatform.user.infrastructure.repository.IamRolePermissionRepository;
import com.company.skillplatform.user.domain.RoleStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultFeishuRoleService {
    private final IamUserRoleRepository userRoles;
    private final IamRoleRepository roles;
    private final IamRolePermissionRepository rolePermissions;

    public DefaultFeishuRoleService(IamUserRoleRepository userRoles, IamRoleRepository roles, IamRolePermissionRepository rolePermissions) {
        this.userRoles = userRoles;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
    }

    @Transactional
    public int grantToUnassignedUsers() {
        var consumer = roles.findByRoleKey("CONSUMER")
                .orElseThrow(() -> new IllegalStateException("CONSUMER role is missing"));
        if (consumer.getStatus() != RoleStatus.ACTIVE || rolePermissions.findPermissionKeysByRoleId(consumer.getId()).isEmpty()) {
            throw new IllegalStateException("CONSUMER role has no active permissions");
        }
        return userRoles.grantConsumerToUnassignedFeishuUsers();
    }
}
