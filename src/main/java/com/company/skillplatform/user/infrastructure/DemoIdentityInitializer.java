package com.company.skillplatform.user.infrastructure;

import com.company.skillplatform.user.domain.IdentityProviderType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@EnableConfigurationProperties(MockAdminProperties.class)
@ConditionalOnProperty(prefix = "skill-platform.auth.mock", name = "enabled", havingValue = "true")
public class DemoIdentityInitializer implements ApplicationRunner {
    static final String TEST_USER = "test-user";
    static final String TEST_MAINTAINER = "test-maintainer";
    static final String TEST_ADMIN = "test-admin";
    static final String TEST_PASSWORD = "Test@123456";
    static final Map<String, String> PERMISSIONS = Map.ofEntries(
            Map.entry("skill:browse", "Browse skills"), Map.entry("skill:download", "Download skills"),
            Map.entry("skill:upload", "Upload skills"), Map.entry("skill:edit", "Edit skills"),
            Map.entry("skill:review", "Review skills"), Map.entry("skill:publish", "Publish skills"),
            Map.entry("skill:offline", "Take skills offline"), Map.entry("admin:identity", "Manage identity"),
            Map.entry("admin:audit", "Browse audit logs"));
    static final Map<String, List<String>> ROLE_PERMISSIONS = roles();
    private final MockAdminProperties properties;
    private final IamUserRepository users;
    private final IamRoleRepository roles;
    private final IamPermissionRepository permissions;
    private final IamUserRoleRepository userRoles;
    private final IamRolePermissionRepository rolePermissions;
    private final PasswordEncoder encoder;
    public DemoIdentityInitializer(MockAdminProperties properties, IamUserRepository users, IamRoleRepository roles,
            IamPermissionRepository permissions, IamUserRoleRepository userRoles,
            IamRolePermissionRepository rolePermissions, PasswordEncoder encoder) {
        this.properties = properties; this.users = users; this.roles = roles; this.permissions = permissions;
        this.userRoles = userRoles; this.rolePermissions = rolePermissions; this.encoder = encoder;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (properties.adminPassword() == null || properties.adminPassword().isBlank())
            throw new IllegalStateException("MOCK_ADMIN_PASSWORD must be configured when Mock authentication is enabled");
        Map<String, IamPermissionEntity> permissionMap = new LinkedHashMap<>();
        PERMISSIONS.forEach((key, name) -> permissionMap.put(key, permissions.findByPermissionKey(key)
                .orElseGet(() -> permissions.save(new IamPermissionEntity(key, name, name)))));
        IamUserEntity admin = users.findByUsername(properties.adminUsername()).orElseGet(() -> users.save(
                new IamUserEntity(IdentityProviderType.MOCK, properties.adminUsername(), properties.adminUsername(),
                        encoder.encode(properties.adminPassword()), properties.adminDisplayName(), null)));
        Map<String, IamRoleEntity> roleMap = new LinkedHashMap<>();
        ROLE_PERMISSIONS.forEach((key, values) -> roleMap.put(key, roles.findByRoleKey(key)
                .orElseGet(() -> roles.save(new IamRoleEntity(key, key, "Built-in " + key + " role")))));
        IamRoleEntity adminRole = roleMap.get("ADMIN");
        if (userRoles.findAllByUserId(admin.getId()).stream().noneMatch(item -> item.getRole().getId().equals(adminRole.getId())))
            userRoles.save(new IamUserRoleEntity(admin, adminRole, admin));
        ROLE_PERMISSIONS.forEach((roleKey, keys) -> {
            IamRoleEntity role = roleMap.get(roleKey);
            List<IamRolePermissionEntity> missing = keys.stream().map(permissionMap::get)
                    .filter(permission -> !rolePermissions.existsByRoleIdAndPermissionId(role.getId(), permission.getId()))
                    .map(permission -> new IamRolePermissionEntity(role, permission, admin)).toList();
            if (!missing.isEmpty()) rolePermissions.saveAll(missing);
        });
        IamUserEntity maintainer = ensureTestUser(TEST_MAINTAINER, "Skill Maintainer");
        IamUserEntity consumer = ensureTestUser(TEST_USER, "Test User");
        IamUserEntity testAdmin = ensureTestUser(TEST_ADMIN, "Test Administrator");
        assignRole(maintainer, roleMap.get("MAINTAINER"), admin);
        assignRole(consumer, roleMap.get("CONSUMER"), admin);
        assignRole(testAdmin, adminRole, admin);
        if (!admin.getUsername().equals(TEST_MAINTAINER) && !admin.getUsername().equals(TEST_USER)) {
            assignRole(admin, adminRole, admin);
        }
    }
    private IamUserEntity ensureTestUser(String username, String displayName) {
        return users.findByUsername(username).orElseGet(() -> users.save(new IamUserEntity(
                IdentityProviderType.MOCK, username, username, encoder.encode(TEST_PASSWORD), displayName, null)));
    }
    private void assignRole(IamUserEntity user, IamRoleEntity role, IamUserEntity actor) {
        if (userRoles.findAllByUserId(user.getId()).stream().noneMatch(item -> item.getRole().getId().equals(role.getId())))
            userRoles.save(new IamUserRoleEntity(user, role, actor));
    }
    private static Map<String, List<String>> roles() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("ADMIN", List.copyOf(PERMISSIONS.keySet()));
        result.put("MAINTAINER", List.of("skill:browse", "skill:download", "skill:upload", "skill:edit"));
        result.put("REVIEWER", List.of("skill:browse", "skill:review"));
        result.put("PUBLISHER", List.of("skill:browse", "skill:publish", "skill:offline"));
        result.put("CONSUMER", List.of("skill:browse", "skill:download"));
        return result;
    }
}
