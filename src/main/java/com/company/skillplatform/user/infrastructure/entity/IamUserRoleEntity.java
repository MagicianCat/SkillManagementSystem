package com.company.skillplatform.user.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "iam_user_role")
public class IamUserRoleEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private IamUserEntity user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private IamRoleEntity role;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private IamUserEntity createdBy;

    protected IamUserRoleEntity() {}
    public IamUserRoleEntity(IamUserEntity user, IamRoleEntity role, IamUserEntity createdBy) {
        this.user = user; this.role = role; this.createdBy = createdBy;
    }
    public IamUserEntity getUser() { return user; }
    public IamRoleEntity getRole() { return role; }
    public IamUserEntity getCreatedBy() { return createdBy; }
}
