package com.company.skillplatform.skill.infrastructure.repository;

import com.company.skillplatform.skill.infrastructure.entity.SkillFeedbackEntity;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SkillFeedbackRepository extends JpaRepository<SkillFeedbackEntity, Long> {
    Optional<SkillFeedbackEntity> findBySkillIdAndUserId(Long skillId, Long userId);
    Page<SkillFeedbackEntity> findBySkillIdOrderByTimeCreatedDesc(Long skillId, Pageable pageable);
    long countBySkillId(Long skillId);
    @Query("select avg(f.rating) from SkillFeedbackEntity f where f.skill.id = :skillId")
    Double averageRating(@Param("skillId") Long skillId);
}
