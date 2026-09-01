package com.company.skillplatform.user.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "iam_permission")
public class IamPermissionEntity extends BaseJpaEntity {
    @Column(name = "permission_key", nullable = false, length = 64)
    private String permissionKey;
    @Column(name = "permission_name", nullable = false, length = 128)
    private String permissionName;
    @Column(length = 512)
    private String description;

    protected IamPermissionEntity() {}
    public IamPermissionEntity(String permissionKey, String permissionName, String description) {
        this.permissionKey = permissionKey;
        this.permissionName = permissionName;
        this.description = description;
    }
    public String getPermissionKey() { return permissionKey; }
    public String getPermissionName() { return permissionName; }
    public String getDescription() { return description; }
}
