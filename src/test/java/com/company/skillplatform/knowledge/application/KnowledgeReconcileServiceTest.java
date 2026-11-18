package com.company.skillplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.knowledge.domain.EmbeddingProviderException;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import com.company.skillplatform.wiki.domain.WikiKnowledgeCategory;
import com.company.skillplatform.wiki.application.WikiPromotionStore;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeReconcileServiceTest {
    @Test void categoryDriftIsRepairedWithoutEmbedding() {
        var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var vectors=mock(VectorStorePort.class);var index=mock(KnowledgeIndexService.class);
        var document=document(10L);
        when(document.getKnowledgeCategory()).thenReturn(WikiKnowledgeCategory.SKILL_TOOL);
        when(documents.findByStatusOrderByIdAsc("ACTIVE")).thenReturn(List.of(document));
        when(vectors.documentState(10L,"TEAM")).thenReturn(new VectorStorePort.IndexedDocumentState(2,"new-sha",7L,false,List.of(),List.of(),null));
        when(vectors.indexedDocumentIds()).thenReturn(Set.of(10L));
        var reconcile=new KnowledgeReconcileService(documents,links,vectors,index,mock(KnowledgeIndexEventListener.class),new KnowledgeProperties());

        var result=reconcile.reconcile();

        assertThat(result.rebuilt()).isZero();
        verify(vectors).setKnowledgeCategory(10L,"TEAM","SKILL_TOOL");
        verify(index,never()).reindex(10L);
    }
    @Test void platformCategoryIsRepairedFromApprovedSnapshot() {
        var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var vectors=mock(VectorStorePort.class);var index=mock(KnowledgeIndexService.class);
        var promotion=mock(WikiPromotionStore.class);var document=document(11L);
        when(document.isPlatformVisible()).thenReturn(true);
        when(document.getKnowledgeCategory()).thenReturn(WikiKnowledgeCategory.SKILL_TOOL);
        when(documents.findByStatusOrderByIdAsc("ACTIVE")).thenReturn(List.of(document));
        when(promotion.platform(11L)).thenReturn(new WikiPromotionStore.PlatformSnapshot(11L,1L,"批准标题",null,1,"批准正文","OTHER"));
        when(promotion.platformSkills(11L)).thenReturn(List.of());
        when(vectors.documentState(11L,"TEAM")).thenReturn(new VectorStorePort.IndexedDocumentState(2,"new-sha",7L,false,List.of(),List.of(),"SKILL_TOOL"));
        when(vectors.documentState(11L,"PLATFORM")).thenReturn(new VectorStorePort.IndexedDocumentState(1,sha256("批准正文"),7L,true,List.of(),List.of(),"SKILL_TOOL"));
        when(vectors.indexedDocumentIds()).thenReturn(Set.of(11L));
        var reconcile=new KnowledgeReconcileService(documents,links,vectors,index,mock(KnowledgeIndexEventListener.class),new KnowledgeProperties());
        reconcile.setPromotionStore(promotion);

        reconcile.reconcile();

        verify(vectors).setKnowledgeCategory(11L,"PLATFORM","OTHER");
        verify(vectors,never()).setKnowledgeCategory(11L,"TEAM","OTHER");
        verify(index,never()).reindex(11L);
    }
    @Test void oldVectorsWithoutAudienceAreRebuilt() {
        var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var vectors=mock(VectorStorePort.class);var index=mock(KnowledgeIndexService.class);
        var document=document(10L);when(document.getTeamId()).thenReturn(7L);
        when(documents.findByStatusOrderByIdAsc("ACTIVE")).thenReturn(List.of(document));
        when(vectors.documentState(10L)).thenReturn(new VectorStorePort.IndexedDocumentState(2,"new-sha",7L,false,List.of(),List.of()));
        when(vectors.indexedDocumentIds()).thenReturn(Set.of(10L));
        var reconcile=new KnowledgeReconcileService(documents,links,vectors,index,mock(KnowledgeIndexEventListener.class),new KnowledgeProperties());

        var result=reconcile.reconcile();

        assertThat(result.rebuilt()).isEqualTo(1);
        verify(vectors).documentState(10L,"TEAM");verify(index).reindex(10L);
    }
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

    @Test void quotaExhaustionStopsRemainingRebuildsWithoutDeletingActiveVectors() {
        var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var vectors=mock(VectorStorePort.class);var index=mock(KnowledgeIndexService.class);
        var quota=new EmbeddingProviderException(400,"provider_quota_exceeded",null);
        var first=document(1L);var second=document(2L);
        when(documents.findByStatusOrderByIdAsc("ACTIVE")).thenReturn(List.of(first,second));
        when(vectors.indexedDocumentIds()).thenReturn(Set.of(2L));
        doThrow(quota).when(index).reindex(1L);
        var retry=mock(KnowledgeIndexEventListener.class);
        var reconcile=new KnowledgeReconcileService(documents,links,vectors,index,retry,new KnowledgeProperties());

        var result=reconcile.reconcile();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rebuilt()).isZero();
        assertThat(result.deleted()).isZero();
        verify(retry).stopForQuota(1L,quota);
        verify(retry,never()).scheduleRetry(1L);
        verify(index,never()).reindex(2L);
        verify(vectors,never()).deleteDocument(2L);
    }

    private static WikiDocumentEntity document(Long id) {
        var document=mock(WikiDocumentEntity.class);var revision=mock(WikiDocumentRevisionEntity.class);
        when(document.getId()).thenReturn(id);when(document.getTeamId()).thenReturn(7L);when(document.getCurrentRevision()).thenReturn(revision);
        when(revision.getRevisionNo()).thenReturn(2);when(revision.getContentSha256()).thenReturn("new-sha");
        return document;
    }
    private static String sha256(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
