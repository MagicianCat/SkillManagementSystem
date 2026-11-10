package com.company.skillplatform.wiki.infrastructure.repository;

import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface WikiDocumentRepository extends JpaRepository<WikiDocumentEntity, Long>, JpaSpecificationExecutor<WikiDocumentEntity> {
    Optional<WikiDocumentEntity> findByIdAndStatus(Long id, String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WikiDocumentEntity d where d.id = :id")
    Optional<WikiDocumentEntity> findByIdForUpdate(Long id);
    List<WikiDocumentEntity> findByTitleAndPlatformVisibleTrueAndStatus(String title, String status);
    List<WikiDocumentEntity> findByStatusOrderByIdAsc(String status);
    List<WikiDocumentEntity> findByParentIdAndStatusOrderByIdAsc(Long parentId, String status);
    Optional<WikiDocumentEntity> findFirstBySourceSystemAndSourceResourceIdAndTeamIdAndStatusOrderByIdDesc(String sourceSystem, String sourceResourceId, Long teamId, String status);
}
