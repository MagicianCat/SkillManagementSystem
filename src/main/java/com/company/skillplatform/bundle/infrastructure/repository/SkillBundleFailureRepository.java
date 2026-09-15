package com.company.skillplatform.bundle.infrastructure.repository;

import com.company.skillplatform.bundle.infrastructure.entity.SkillBundleFailureEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillBundleFailureRepository extends JpaRepository<SkillBundleFailureEntity, Long> {
    List<SkillBundleFailureEntity> findByBundleId(Long bundleId);
}
