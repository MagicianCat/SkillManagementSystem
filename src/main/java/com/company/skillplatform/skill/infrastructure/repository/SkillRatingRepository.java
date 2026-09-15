package com.company.skillplatform.skill.infrastructure.repository;

import com.company.skillplatform.skill.infrastructure.entity.SkillRatingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SkillRatingRepository extends JpaRepository<SkillRatingEntity, Long> {
    Optional<SkillRatingEntity> findBySkillIdAndUserId(Long skillId, Long userId);
    long countBySkillId(Long skillId);
    @Query("select avg(r.rating) from SkillRatingEntity r where r.skill.id = :skillId")
    Double averageRating(@Param("skillId") Long skillId);
}
