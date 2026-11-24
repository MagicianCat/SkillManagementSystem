package com.company.skillplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.knowledge.domain.*;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import com.company.skillplatform.wiki.application.WikiPromotionStore;
import com.company.skillplatform.wiki.domain.WikiKnowledgeCategory;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeSearchServiceTest {
    @Test void roleRoutingRechecksDatabaseAndOnlyAddsLocalFallback() {
        var embedding=mock(EmbeddingPort.class);
        var vectors=mock(VectorStorePort.class);
        var wiki=mock(WikiDocumentService.class);
        var documents=mock(WikiDocumentRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);
        var lookup=mock(ExternalRoleLookupService.class);
        var resolver=mock(ExternalWikiRoleResolver.class);
        var sync=mock(KnowledgeRoleMetadataSync.class);
        var settings=new KnowledgeProperties();
        settings.getRerank().setEnabled(false);
        when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        when(lookup.requireVisible(List.of(54L),List.of(7L))).thenReturn(
                List.of(new ExternalRoleLookupService.Role(54,"A","source","a")));
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);
        var fixtures=List.of(
                teamDocument(1L,1,"sha",WikiKnowledgeCategory.PROJECT_CONTEXT),
                teamDocument(2L,1,"sha",WikiKnowledgeCategory.PROJECT_CONTEXT),
                teamDocument(3L,1,"sha",WikiKnowledgeCategory.PROJECT_CONTEXT));
        when(documents.findAllById(any())).thenReturn(fixtures);
        when(resolver.resolveAll(any())).thenAnswer(call -> {
            List<Long> ids=call.getArgument(0);
            Map<Long,ExternalRoleMetadata> metadata=new HashMap<>();
            if(ids.contains(1L))metadata.put(1L,new ExternalRoleMetadata("source","a","DOCUMENT",List.of(new ExternalRoleMetadata.Role(54,"A","a"))));
            if(ids.contains(2L))metadata.put(2L,new ExternalRoleMetadata("source","b","DOCUMENT",List.of(new ExternalRoleMetadata.Role(58,"B","b"))));
            if(ids.contains(3L))metadata.put(3L,ExternalRoleMetadata.LOCAL);
            return metadata;
        });
        var a=hit("a",1L,"TEAM",1,"sha","PROJECT_CONTEXT",0.5);
        var b=hit("b",2L,"TEAM",1,"sha","PROJECT_CONTEXT",0.9);
        var general=hit("general",3L,"TEAM",1,"sha","PROJECT_CONTEXT",1);
        when(vectors.search(any(),any(),anyInt())).thenAnswer(call -> {
            VectorStorePort.KnowledgeFilter filter=call.getArgument(1);
            return filter.generalOnly()?List.of(b,general):List.of(b,a);
        });
        var service=new KnowledgeSearchService(embedding,mock(RerankPort.class),vectors,wiki,documents,links,settings,new SimpleMeterRegistry());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"roleLookup",lookup);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"roleResolver",resolver);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"roleSync",sync);

        var strict=service.search(9,"USER_VISIBLE","背景",null,"PROJECT_CONTEXT","STRICT",5,List.of(54L),"STRICT");
        assertThat(strict.results()).extracting(KnowledgeSearchResult.DocumentResult::documentId).containsExactly(1L);
        assertThat(strict.roleFallbackUsed()).isFalse();
        var preferred=service.search(9,"USER_VISIBLE","背景",null,"PROJECT_CONTEXT","STRICT",5,List.of(54L),"PREFER");
        assertThat(preferred.results()).extracting(KnowledgeSearchResult.DocumentResult::documentId).containsExactly(1L,3L);
        assertThat(preferred.results()).extracting(KnowledgeSearchResult.DocumentResult::roleMatch)
                .containsExactly("MATCHED","GENERAL_FALLBACK");
        verify(sync,atLeastOnce()).requestRepair(2L);
    }

    @Test void fdeCategoryCanBeStrictlyFilteredWithoutChangingTeamVisibility() {
        var embedding=mock(EmbeddingPort.class);var rerank=mock(RerankPort.class);var vectors=mock(VectorStorePort.class);
        var wiki=mock(WikiDocumentService.class);var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var properties=new KnowledgeProperties();properties.getRerank().setEnabled(false);
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);
        when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        var fde=teamDocument(15L,1,"fde-sha",WikiKnowledgeCategory.FDE_WIKI);
        when(documents.findAllById(any())).thenReturn(List.of(fde));
        when(vectors.search(any(),any(),anyInt())).thenReturn(List.of(hit("fde",15L,"TEAM",1,"fde-sha","FDE_WIKI",0.9)));
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,documents,links,properties,new SimpleMeterRegistry());

        var result=service.search(9L,"USER_VISIBLE","FDE 经验",null,"FDE_WIKI","STRICT",5);

        assertThat(result.results()).extracting(KnowledgeSearchResult.DocumentResult::knowledgeCategory).containsExactly("FDE_WIKI");
        var filter=ArgumentCaptor.forClass(VectorStorePort.KnowledgeFilter.class);
        verify(vectors).search(any(),filter.capture(),anyInt());
        assertThat(filter.getValue().knowledgeCategory()).isEqualTo("FDE_WIKI");
    }
    @Test void preferFallsBackOnceWithSameEmbeddingAndStrictStaysInCategory() {
        var embedding=mock(EmbeddingPort.class);var rerank=mock(RerankPort.class);var vectors=mock(VectorStorePort.class);
        var wiki=mock(WikiDocumentService.class);var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var properties=new KnowledgeProperties();properties.getRerank().setEnabled(false);
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);
        when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        var skillHit=hit("skill",12L,"TEAM",2,"team-sha","SKILL_TOOL",0.9);
        var contextHit=hit("context",13L,"TEAM",1,"context-sha","PROJECT_CONTEXT",0.8);
        when(vectors.search(any(),any(),anyInt())).thenAnswer(invocation->{
            VectorStorePort.KnowledgeFilter filter=invocation.getArgument(1);
            return filter.knowledgeCategory()==null?List.of(skillHit,contextHit):List.of(skillHit);
        });
        var skillDoc=teamDocument(12L,2,"team-sha",WikiKnowledgeCategory.SKILL_TOOL);
        var contextDoc=teamDocument(13L,1,"context-sha",WikiKnowledgeCategory.PROJECT_CONTEXT);
        when(documents.findAllById(any())).thenReturn(List.of(skillDoc,contextDoc));
        when(documents.findById(12L)).thenReturn(Optional.of(skillDoc));
        when(documents.findById(13L)).thenReturn(Optional.of(contextDoc));
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,documents,links,properties,new SimpleMeterRegistry());

        var preferred=service.search(9L,"USER_VISIBLE","安装工具",null,"SKILL_TOOL","PREFER",5);
        assertThat(preferred.results()).extracting(KnowledgeSearchResult.DocumentResult::knowledgeCategory).containsExactly("SKILL_TOOL","PROJECT_CONTEXT");
        verify(embedding,times(1)).embedQuery(anyString());
        verify(vectors,times(2)).search(any(),any(),anyInt());

        clearInvocations(vectors,embedding);
        var strict=service.search(9L,"USER_VISIBLE","安装工具",null,"SKILL_TOOL","STRICT",5);
        assertThat(strict.results()).hasSize(1);
        assertThat(strict.results().get(0).knowledgeCategory()).isEqualTo("SKILL_TOOL");
        verify(vectors,times(1)).search(any(),any(),anyInt());
    }

    @Test void teamMemberGetsOneConsistentTeamVersionWhenBothAudiencesAreReturned() {
        var embedding=mock(EmbeddingPort.class);var rerank=mock(RerankPort.class);var vectors=mock(VectorStorePort.class);
        var wiki=mock(WikiDocumentService.class);var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var promotion=mock(WikiPromotionStore.class);var properties=new KnowledgeProperties();properties.getRerank().setEnabled(false);
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);
        when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        var document=teamDocument(12L,2,"team-sha",WikiKnowledgeCategory.SKILL_TOOL);
        when(document.isPlatformVisible()).thenReturn(true);
        when(documents.findAllById(any())).thenReturn(List.of(document));
        when(documents.findById(12L)).thenReturn(Optional.of(document));
        String approvedText="批准正文";
        when(promotion.platform(12L)).thenReturn(new WikiPromotionStore.PlatformSnapshot(12L,1L,"批准标题",null,1,approvedText,"OTHER"));
        when(vectors.search(any(),any(),anyInt())).thenReturn(List.of(
                hit("public",12L,"PLATFORM",1,sha256(approvedText),"OTHER",0.95),
                hit("team",12L,"TEAM",2,"team-sha","SKILL_TOOL",0.8)));
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,documents,links,properties,new SimpleMeterRegistry());
        service.setPromotionStore(promotion);

        var result=service.search(9L,"USER_VISIBLE","工具",null,5);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).teamScope()).isEqualTo("TEAM");
        assertThat(result.results().get(0).knowledgeCategory()).isEqualTo("SKILL_TOOL");
        assertThat(result.results().get(0).snippets()).extracting(KnowledgeSearchResult.Snippet::content).containsExactly("TEAM content");
    }

    @Test void strictCategoryCannotExposeAnotherTeamsPrivateWiki() {
        var embedding=mock(EmbeddingPort.class);var rerank=mock(RerankPort.class);var vectors=mock(VectorStorePort.class);
        var wiki=mock(WikiDocumentService.class);var documents=mock(WikiDocumentRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var properties=new KnowledgeProperties();properties.getRerank().setEnabled(false);
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);
        when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        when(vectors.search(any(),any(),anyInt())).thenReturn(List.of(hit("private",14L,"TEAM",1,"private-sha","SKILL_TOOL",0.99)));
        var otherTeam=teamDocument(14L,1,"private-sha",WikiKnowledgeCategory.SKILL_TOOL);
        when(otherTeam.getTeamId()).thenReturn(8L);
        when(documents.findAllById(any())).thenReturn(List.of(otherTeam));
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,documents,links,properties,new SimpleMeterRegistry());

        var result=service.search(9L,"USER_VISIBLE","私有资料",null,"SKILL_TOOL","STRICT",5);

        assertThat(result.results()).isEmpty();
        verify(vectors,times(1)).search(any(),any(),anyInt());
        verifyNoInteractions(links);
    }

    private static WikiDocumentEntity teamDocument(long id,int revisionNo,String sha,WikiKnowledgeCategory category){
        var document=mock(WikiDocumentEntity.class);var revision=mock(WikiDocumentRevisionEntity.class);
        when(document.getId()).thenReturn(id);when(document.getStatus()).thenReturn("ACTIVE");when(document.getTeamId()).thenReturn(7L);
        when(document.getCurrentRevision()).thenReturn(revision);when(document.getKnowledgeCategory()).thenReturn(category);
        when(revision.getRevisionNo()).thenReturn(revisionNo);when(revision.getContentSha256()).thenReturn(sha);
        return document;
    }
    private static VectorStorePort.VectorSearchHit hit(String id,long documentId,String audience,int revisionNo,String sha,String category,double score){
        Map<String,Object> payload=new HashMap<>();
        payload.put("documentId",documentId);payload.put("audience",audience);payload.put("platformVisible","PLATFORM".equals(audience));
        payload.put("revisionNo",revisionNo);payload.put("contentSha256",sha);payload.put("title","PLATFORM".equals(audience)?"批准标题":"TEAM title");
        payload.put("content",audience+" content");payload.put("knowledgeCategory",category);
        return new VectorStorePort.VectorSearchHit(id,score,payload);
    }
    private static String sha256(String text){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception exception){throw new IllegalStateException(exception);}
    }
    @Test
    void derivesTeamsFromUserContextAndFallsBackWhenRerankFails() {
        EmbeddingPort embedding=mock(EmbeddingPort.class);RerankPort rerank=mock(RerankPort.class);VectorStorePort vectors=mock(VectorStorePort.class);
        WikiDocumentService wiki=mock(WikiDocumentService.class);WikiDocumentRepository documents=mock(WikiDocumentRepository.class);WikiDocumentSkillRepository links=mock(WikiDocumentSkillRepository.class);KnowledgeProperties properties=new KnowledgeProperties();
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        var searchHit=hit("p1",12L,"TEAM",2,"sha-new","OTHER",0.8);
        searchHit.payload().put("heading","测试");searchHit.payload().put("headingPath",List.of("测试"));
        searchHit.payload().put("content","执行单元测试");searchHit.payload().put("developmentStages",List.of("TESTING"));
        when(vectors.search(any(),any(),anyInt())).thenReturn(List.of(searchHit));
        WikiDocumentEntity current=mock(WikiDocumentEntity.class);WikiDocumentRevisionEntity revision=mock(WikiDocumentRevisionEntity.class);
        when(current.getId()).thenReturn(12L);when(current.getStatus()).thenReturn("ACTIVE");when(current.getTeamId()).thenReturn(7L);when(current.getCurrentRevision()).thenReturn(revision);
        when(current.getKnowledgeCategory()).thenReturn(WikiKnowledgeCategory.OTHER);
        when(revision.getRevisionNo()).thenReturn(2);when(revision.getContentSha256()).thenReturn("sha-new");when(documents.findAllById(any())).thenReturn(List.of(current));
        when(rerank.rerank(anyString(),anyList(),anyInt())).thenThrow(new IllegalStateException("down"));when(links.findByDocumentIdOrderBySortOrderAsc(12L)).thenReturn(List.of());
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,documents,links,properties,new SimpleMeterRegistry());

        var result=service.search(9L,"USER_VISIBLE","怎么做好测试","TESTING",5);

        assertThat(result.retrieverUnavailable()).isFalse();assertThat(result.rerankApplied()).isFalse();assertThat(result.results()).hasSize(1);
        var filter=ArgumentCaptor.forClass(VectorStorePort.KnowledgeFilter.class);verify(vectors).search(any(),filter.capture(),eq(12));assertThat(filter.getValue().visibleTeamIds()).containsExactly(7L);assertThat(filter.getValue().platformOnly()).isFalse();
    }

    @Test
    void publicScopeNeverLoadsUserTeams() {
        EmbeddingPort embedding=mock(EmbeddingPort.class);RerankPort rerank=mock(RerankPort.class);VectorStorePort vectors=mock(VectorStorePort.class);
        WikiDocumentService wiki=mock(WikiDocumentService.class);WikiDocumentRepository documents=mock(WikiDocumentRepository.class);WikiDocumentSkillRepository links=mock(WikiDocumentSkillRepository.class);KnowledgeProperties properties=new KnowledgeProperties();properties.getRerank().setEnabled(false);
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);when(vectors.search(any(),any(),anyInt())).thenReturn(List.of());
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,documents,links,properties,new SimpleMeterRegistry());

        service.search(9L,"PLATFORM_PUBLIC_ONLY","平台规范",null,5);

        var filter=ArgumentCaptor.forClass(VectorStorePort.KnowledgeFilter.class);verify(vectors).search(any(),filter.capture(),anyInt());assertThat(filter.getValue().platformOnly()).isTrue();assertThat(filter.getValue().visibleTeamIds()).isEmpty();verifyNoInteractions(wiki);
    }

    @Test
    void dropsOldRevisionUntilReindexFinishes() {
        EmbeddingPort embedding=mock(EmbeddingPort.class);RerankPort rerank=mock(RerankPort.class);VectorStorePort vectors=mock(VectorStorePort.class);
        WikiDocumentService wiki=mock(WikiDocumentService.class);WikiDocumentRepository documents=mock(WikiDocumentRepository.class);WikiDocumentSkillRepository links=mock(WikiDocumentSkillRepository.class);
        KnowledgeProperties properties=new KnowledgeProperties();properties.getRerank().setEnabled(false);
        when(embedding.embedQuery(anyString())).thenReturn(new float[1024]);when(wiki.visibleTeams(9L)).thenReturn(List.of(new WikiDocumentService.TeamView(7L,"A",null)));
        when(vectors.search(any(),any(),anyInt())).thenReturn(List.of(new VectorStorePort.VectorSearchHit("p1",0.8,Map.of("documentId",12,"audience","TEAM","revisionNo",1,"contentSha256","sha-old","title","旧标题","documentType","SKILL_GUIDE","teamId",7,"heading","旧内容","content","旧内容"))));
        WikiDocumentEntity current=mock(WikiDocumentEntity.class);WikiDocumentRevisionEntity revision=mock(WikiDocumentRevisionEntity.class);
        when(current.getId()).thenReturn(12L);when(current.getStatus()).thenReturn("ACTIVE");when(current.getTeamId()).thenReturn(7L);when(current.getCurrentRevision()).thenReturn(revision);
        when(revision.getRevisionNo()).thenReturn(2);when(revision.getContentSha256()).thenReturn("sha-new");when(documents.findAllById(any())).thenReturn(List.of(current));
        var service=new KnowledgeSearchService(embedding,rerank,vectors,wiki,documents,links,properties,new SimpleMeterRegistry());

        var result=service.search(9L,"USER_VISIBLE","新文档内容",null,5);

        assertThat(result.retrieverUnavailable()).isTrue();assertThat(result.results()).isEmpty();verifyNoInteractions(links);
    }
}
