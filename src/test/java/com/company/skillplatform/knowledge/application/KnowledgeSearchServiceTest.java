package com.company.skillplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.knowledge.domain.*;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeSearchServiceTest {
    @Test
    void derivesTeamsFromUserContextAndFallsBackWhenRerankFails() {
        EmbeddingPort embedding=mock(EmbeddingPort.class);RerankPort rerank=mock(RerankPort.class);VectorStorePort vectors=mock(VectorStorePort.class);
        WikiDocumentService wiki=mock(WikiDocumentService.class);WikiDocumentSkillRepository links=mock(WikiDocumentSkillRepository.class);KnowledgeProperties properties=new KnowledgeProperties();
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        when(vectors.search(any(),any(),anyInt())).thenReturn(List.of(new VectorStorePort.VectorSearchHit("p1",0.8,Map.of("documentId",12,"title","研发全流程最佳实践","documentType","SKILL_GUIDE","teamId",7,"heading","测试","headingPath",List.of("测试"),"content","执行单元测试","developmentStages",List.of("TESTING")))));
        when(rerank.rerank(anyString(),anyList(),anyInt())).thenThrow(new IllegalStateException("down"));when(links.findByDocumentIdOrderBySortOrderAsc(12L)).thenReturn(List.of());
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,links,properties,new SimpleMeterRegistry());

        var result=service.search(9L,"USER_VISIBLE","怎么做好测试","TESTING",5);

        assertThat(result.retrieverUnavailable()).isFalse();assertThat(result.rerankApplied()).isFalse();assertThat(result.results()).hasSize(1);
        var filter=ArgumentCaptor.forClass(VectorStorePort.KnowledgeFilter.class);verify(vectors).search(any(),filter.capture(),eq(12));assertThat(filter.getValue().visibleTeamIds()).containsExactly(7L);assertThat(filter.getValue().platformOnly()).isFalse();
    }

    @Test
    void publicScopeNeverLoadsUserTeams() {
        EmbeddingPort embedding=mock(EmbeddingPort.class);RerankPort rerank=mock(RerankPort.class);VectorStorePort vectors=mock(VectorStorePort.class);
        WikiDocumentService wiki=mock(WikiDocumentService.class);WikiDocumentSkillRepository links=mock(WikiDocumentSkillRepository.class);KnowledgeProperties properties=new KnowledgeProperties();properties.getRerank().setEnabled(false);
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);when(vectors.search(any(),any(),anyInt())).thenReturn(List.of());
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,links,properties,new SimpleMeterRegistry());

        service.search(9L,"PLATFORM_PUBLIC_ONLY","平台规范",null,5);

        var filter=ArgumentCaptor.forClass(VectorStorePort.KnowledgeFilter.class);verify(vectors).search(any(),filter.capture(),anyInt());assertThat(filter.getValue().platformOnly()).isTrue();assertThat(filter.getValue().visibleTeamIds()).isEmpty();verifyNoInteractions(wiki);
    }
}
