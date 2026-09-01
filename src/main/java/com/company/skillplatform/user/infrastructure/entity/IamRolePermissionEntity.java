package com.company.skillplatform.user.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "iam_role_permission")
public class IamRolePermissionEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private IamRoleEntity role;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    private IamPermissionEntity permission;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private IamUserEntity createdBy;

    protected IamRolePermissionEntity() {}
    public IamRolePermissionEntity(IamRoleEntity role, IamPermissionEntity permission, IamUserEntity createdBy) {
        this.role = role; this.permission = permission; this.createdBy = createdBy;
    }
    public IamRoleEntity getRole() { return role; }
    public IamPermissionEntity getPermission() { return permission; }
    public IamUserEntity getCreatedBy() { return createdBy; }
}
