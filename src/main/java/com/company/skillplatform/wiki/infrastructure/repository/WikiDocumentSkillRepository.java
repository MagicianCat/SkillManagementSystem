package com.company.skillplatform.wiki.infrastructure.repository;

import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentSkillEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiDocumentSkillRepository extends JpaRepository<WikiDocumentSkillEntity, WikiDocumentSkillEntity.Id> {
    List<WikiDocumentSkillEntity> findByDocumentIdOrderBySortOrderAsc(Long documentId);
    List<WikiDocumentSkillEntity> findBySkillId(Long skillId);
    void deleteByDocumentId(Long documentId);
}
