package com.company.skillplatform.wiki.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentSkillEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WikiPromotionStoreTest {
    @Test void collectsDeepDescendantsAndDeduplicatesSkills() {
        var documents=mock(WikiDocumentRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);
        var root=node(1L,null);var child=node(2L,root);var grandchild=node(3L,child);var greatGrandchild=node(4L,grandchild);
        when(documents.findByParentIdAndStatusOrderByIdAsc(1L,"ACTIVE")).thenReturn(List.of(child));
        when(documents.findByParentIdAndStatusOrderByIdAsc(2L,"ACTIVE")).thenReturn(List.of(grandchild));
        when(documents.findByParentIdAndStatusOrderByIdAsc(3L,"ACTIVE")).thenReturn(List.of(greatGrandchild));
        when(documents.findByParentIdAndStatusOrderByIdAsc(4L,"ACTIVE")).thenReturn(List.of());
        var skillA=skill(10L);var skillB=skill(11L);
        var rootLink=link(skillA);var childLink=link(skillB);var grandchildLink=link(skillA);
        when(links.findByDocumentIdOrderBySortOrderAsc(1L)).thenReturn(List.of(rootLink));
        when(links.findByDocumentIdOrderBySortOrderAsc(2L)).thenReturn(List.of(childLink));
        when(links.findByDocumentIdOrderBySortOrderAsc(3L)).thenReturn(List.of(grandchildLink));
        when(links.findByDocumentIdOrderBySortOrderAsc(4L)).thenReturn(List.of());
        var store=new WikiPromotionStore(documents,links,mock(JdbcTemplate.class));

        var tree=store.collect(root);

        assertThat(tree.items()).extracting(WikiPromotionStore.Item::documentId).containsExactly(1L,2L,3L,4L);
        assertThat(tree.skills()).extracting(WikiPromotionStore.SkillItem::skillId).containsExactly(10L,11L);
        assertThat(tree.fingerprint()).hasSize(64);
    }

    private WikiDocumentEntity node(Long id,WikiDocumentEntity parent) {
        var document=mock(WikiDocumentEntity.class);var revision=mock(WikiDocumentRevisionEntity.class);
        when(document.getId()).thenReturn(id);when(document.getTeamId()).thenReturn(7L);
        when(document.getStatus()).thenReturn("ACTIVE");when(document.getTitle()).thenReturn("Wiki "+id);
        when(document.getParent()).thenReturn(parent);when(document.getCurrentRevision()).thenReturn(revision);
        when(revision.getId()).thenReturn(100L+id);when(revision.getRevisionNo()).thenReturn(1);
        when(revision.getContentSha256()).thenReturn("sha"+id);
        return document;
    }

    private SkillEntity skill(Long id) {
        var skill=mock(SkillEntity.class);var version=mock(SkillVersionEntity.class);
        when(skill.getId()).thenReturn(id);when(skill.getSkillKey()).thenReturn("skill-"+id);
        when(skill.getStatus()).thenReturn(SkillStatus.ACTIVE);when(skill.getScopeType()).thenReturn("TEAM");
        when(skill.getTeamId()).thenReturn(7L);when(skill.getLatestPublishedVersion()).thenReturn(version);
        when(version.getId()).thenReturn(id+1000);when(version.getLifecycleStatus()).thenReturn(LifecycleStatus.PUBLISHED);
        return skill;
    }

    private WikiDocumentSkillEntity link(SkillEntity skill) {
        var link=mock(WikiDocumentSkillEntity.class);when(link.getSkill()).thenReturn(skill);return link;
    }
}
