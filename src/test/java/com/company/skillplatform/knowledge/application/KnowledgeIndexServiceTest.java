package com.company.skillplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.skillplatform.knowledge.domain.EmbeddingPort;
import com.company.skillplatform.knowledge.domain.KnowledgeVectorPoint;
import com.company.skillplatform.knowledge.domain.MarkdownHeadingChunker;
import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeIndexServiceTest {
    @Test void indexesTeamGuideWithoutSkillsWithEmptySkillAclPayload() {
        var documents = mock(WikiDocumentRepository.class);
        var links = mock(WikiDocumentSkillRepository.class);
        var embedding = mock(EmbeddingPort.class);
        var vectors = mock(VectorStorePort.class);
        var indexedPoints = new AtomicReference<List<KnowledgeVectorPoint>>();
        var actor = mock(IamUserEntity.class);
        var document = new WikiDocumentEntity("独立团队知识", "SKILL_GUIDE", 7L, false, actor);
        ReflectionTestUtils.setField(document, "id", 91L);
        var revision = new WikiDocumentRevisionEntity(document, 1, "# 接口约定\n\n不关联 Skill 的知识内容。", "sha", actor);
        document.setCurrentRevision(revision, actor);
        when(documents.findById(91L)).thenReturn(Optional.of(document));
        when(links.findByDocumentIdOrderBySortOrderAsc(91L)).thenReturn(List.of());
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).stream().map(ignored -> new float[] {0.1f}).toList());
        doAnswer(invocation -> {
            indexedPoints.set(invocation.getArgument(1));
            return null;
        }).when(vectors).replaceDocument(eq(91L), anyList());

        var service = new KnowledgeIndexService(documents, links, new MarkdownHeadingChunker(3000, 400), embedding,
                vectors, new KnowledgeProperties(), new SimpleMeterRegistry());
        service.reindex(91L);

        verify(vectors).replaceDocument(eq(91L), anyList());
        assertThat(indexedPoints.get()).isNotEmpty();
        assertThat(indexedPoints.get()).allSatisfy(point -> {
            assertThat(point.payload().get("skillKeys")).isEqualTo(List.of());
            assertThat(point.payload().get("developmentStages")).isEqualTo(List.of());
            assertThat(point.payload().get("teamId")).isEqualTo(7L);
        });
    }
}
