package com.company.skillplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeReconcileServiceTest {
    @Test void oneFailedDocumentDoesNotPreventOtherWikiUpdates() {
        var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var vectors=mock(VectorStorePort.class);var index=mock(KnowledgeIndexService.class);
        var first=document(1L);var second=document(2L);
        when(documents.findByStatusOrderByIdAsc("ACTIVE")).thenReturn(List.of(first,second));
        when(vectors.indexedDocumentIds()).thenReturn(Set.of());
        doThrow(new IllegalStateException("embedding unavailable")).when(index).reindex(1L);
        var retry=mock(KnowledgeIndexEventListener.class);
        var reconcile=new KnowledgeReconcileService(documents,links,vectors,index,retry,new KnowledgeProperties());

        var result=reconcile.reconcile();

        assertThat(result.failed()).isEqualTo(1);assertThat(result.rebuilt()).isEqualTo(1);
        verify(index).reindex(2L);verify(retry).scheduleRetry(1L);
    }

    private static WikiDocumentEntity document(Long id) {
        var document=mock(WikiDocumentEntity.class);var revision=mock(WikiDocumentRevisionEntity.class);
        when(document.getId()).thenReturn(id);when(document.getCurrentRevision()).thenReturn(revision);
        when(revision.getRevisionNo()).thenReturn(2);when(revision.getContentSha256()).thenReturn("new-sha");
        return document;
    }
}
