package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "virtual_project")
public class VirtualProjectEntity extends BaseJpaEntity {
    @Column(name = "project_key", nullable = false, unique = true, length = 36, columnDefinition = "char(36)")
    private String projectKey;
    @Column(name = "project_name", nullable = false, length = 255)
    private String projectName;
    @Column(length = 2000)
    private String description;
    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private IamUserEntity createdBy;
    @Version
    @Column(name = "version_no", nullable = false)
    private int versionNo;

    protected VirtualProjectEntity() {}

    public VirtualProjectEntity(String name, String description, IamUserEntity actor) {
        this.projectKey = UUID.randomUUID().toString();
        this.projectName = name;
        this.description = description;
        this.createdBy = actor;
    }

    public void update(String name, String value) { this.projectName = name; this.description = value; }
    public void archive() { this.status = "ARCHIVED"; }
    public String getProjectKey() { return projectKey; }
    public String getProjectName() { return projectName; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public IamUserEntity getCreatedBy() { return createdBy; }
    public int getVersionNo() { return versionNo; }
}
