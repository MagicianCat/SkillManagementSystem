package com.company.skillplatform.workflow.infrastructure.repository;

import com.company.skillplatform.workflow.infrastructure.entity.SkillReviewWikiRevisionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillReviewWikiRevisionRepository extends JpaRepository<SkillReviewWikiRevisionEntity, SkillReviewWikiRevisionEntity.Id> {
    List<SkillReviewWikiRevisionEntity> findByReviewId(Long reviewId);
}
