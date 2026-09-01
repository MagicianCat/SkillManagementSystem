package com.company.skillplatform.user.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.domain.RoleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "iam_role")
public class IamRoleEntity extends BaseJpaEntity {
    @Column(name = "role_key", nullable = false, length = 64)
    private String roleKey;
    @Column(name = "role_name", nullable = false, length = 128)
    private String roleName;
    @Column(length = 512)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoleStatus status;
    @Version
    @Column(name = "version_no", nullable = false)
    private int versionNo;

    protected IamRoleEntity() {}
    public IamRoleEntity(String roleKey, String roleName, String description) {
        this.roleKey = roleKey;
        this.roleName = roleName;
        this.description = description;
        this.status = RoleStatus.ACTIVE;
    }
    public String getRoleKey() { return roleKey; }
    public String getRoleName() { return roleName; }
    public String getDescription() { return description; }
    public RoleStatus getStatus() { return status; }
    public int getVersionNo() { return versionNo; }
}
