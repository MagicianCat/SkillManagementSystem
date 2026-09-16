package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "virtual_project_member", uniqueConstraints = @UniqueConstraint(name = "uk_virtual_project_member", columnNames = {"project_id", "user_id"}))
public class VirtualProjectMemberEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private VirtualProjectEntity project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private IamUserEntity user;
    @Column(name = "role_key", nullable = false, length = 32) private String roleKey;
    @Column(nullable = false, length = 32) private String status = "ACTIVE";
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by", nullable = false) private IamUserEntity createdBy;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;
    protected VirtualProjectMemberEntity() {}
    public VirtualProjectMemberEntity(VirtualProjectEntity project, IamUserEntity user, String roleKey, IamUserEntity actor) {
        this.project = project; this.user = user; this.roleKey = roleKey; this.createdBy = actor;
    }
    public void changeRole(String value) { this.roleKey = value; }
    public void deactivate() { this.status = "INACTIVE"; }
    public VirtualProjectEntity getProject() { return project; }
    public IamUserEntity getUser() { return user; }
    public String getRoleKey() { return roleKey; }
    public String getStatus() { return status; }
    public int getVersionNo() { return versionNo; }
}
