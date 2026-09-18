package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.*;
import java.util.List;
import org.springframework.data.jpa.repository.*;

public interface DocumentAgentSessionWikiContextRepository extends JpaRepository<DocumentAgentSessionWikiContextEntity, Long> {
    @EntityGraph(attributePaths = {"wikiDocument", "wikiDocument.currentRevision", "addedBy"})
    List<DocumentAgentSessionWikiContextEntity> findBySessionOrderByOrdinalNoAsc(DocumentAgentSessionEntity session);
    void deleteBySession(DocumentAgentSessionEntity session);
}
