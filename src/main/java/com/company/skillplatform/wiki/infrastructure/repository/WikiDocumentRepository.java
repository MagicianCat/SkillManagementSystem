package com.company.skillplatform.wiki.infrastructure.repository;

import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface WikiDocumentRepository extends JpaRepository<WikiDocumentEntity, Long>, JpaSpecificationExecutor<WikiDocumentEntity> {
    Optional<WikiDocumentEntity> findByIdAndStatus(Long id, String status);
    List<WikiDocumentEntity> findByTitleAndPlatformVisibleTrueAndStatus(String title, String status);
    List<WikiDocumentEntity> findByStatusOrderByIdAsc(String status);
    List<WikiDocumentEntity> findByParentIdAndStatusOrderByIdAsc(Long parentId, String status);
    Optional<WikiDocumentEntity> findFirstBySourceSystemAndSourceResourceIdAndTeamIdAndStatusOrderByIdDesc(String sourceSystem, String sourceResourceId, Long teamId, String status);
}
