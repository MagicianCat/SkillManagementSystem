package com.company.skillplatform.skill.infrastructure.repository;

import com.company.skillplatform.skill.infrastructure.entity.SkillCommentEntity;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillCommentRepository extends JpaRepository<SkillCommentEntity, Long> {
    Page<SkillCommentEntity> findBySkillIdOrderByTimeCreatedDesc(Long skillId, Pageable pageable);
    Optional<SkillCommentEntity> findByIdAndSkillId(Long id, Long skillId);
}
