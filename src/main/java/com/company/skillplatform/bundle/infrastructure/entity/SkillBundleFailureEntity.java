package com.company.skillplatform.bundle.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "skill_bundle_failure", uniqueConstraints = @UniqueConstraint(name = "uk_skill_bundle_failure", columnNames = {"bundle_id", "skill_key"}))
public class SkillBundleFailureEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "bundle_id") private SkillBundleEntity bundle;
    @Column(name = "skill_key", nullable = false, length = 128) private String skillKey;
    @Column(name = "display_name", length = 255) private String displayName;
    @Column(name = "error_code", nullable = false, length = 64) private String errorCode;
    @Column(name = "error_message", nullable = false, length = 1024) private String errorMessage;
    protected SkillBundleFailureEntity() {}
    public SkillBundleFailureEntity(SkillBundleEntity bundle, String skillKey, String displayName, String errorCode, String errorMessage) {
        this.bundle = bundle; this.skillKey = skillKey; this.displayName = displayName; this.errorCode = errorCode; this.errorMessage = errorMessage;
    }
    public String getSkillKey(){return skillKey;} public String getDisplayName(){return displayName;} public String getErrorCode(){return errorCode;} public String getErrorMessage(){return errorMessage;}
}
