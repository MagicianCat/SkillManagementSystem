package com.company.skillplatform.wiki.infrastructure.repository;

import com.company.skillplatform.wiki.infrastructure.entity.WikiPlatformSnapshotEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiPlatformSnapshotRepository extends JpaRepository<WikiPlatformSnapshotEntity, Long> {
    List<WikiPlatformSnapshotEntity> findByApprovedParentIdOrderByDocumentIdAsc(Long parentId);
}
