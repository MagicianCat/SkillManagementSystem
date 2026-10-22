package com.company.skillplatform.wiki.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillOwnerRepository;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.*;
import com.company.skillplatform.wiki.infrastructure.entity.*;
import com.company.skillplatform.wiki.infrastructure.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class WikiDocumentServiceTest {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void updatingTeamWikiReplacesLinkedSkillsAndPublishesReindexEvent() {
        var documents=mock(WikiDocumentRepository.class);var revisions=mock(WikiDocumentRevisionRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var skills=mock(SkillRepository.class);
        var users=mock(IamUserRepository.class);var events=mock(ApplicationEventPublisher.class);
        var actor=mock(IamUserEntity.class);
        var oldSkill=skill(1L,"old-skill");var newSkill=skill(2L,"new-skill");
        var document=new WikiDocumentEntity("旧标题","SKILL_GUIDE",7L,false,actor);
        ReflectionTestUtils.setField(document,"id",12L);
        document.setCurrentRevision(new WikiDocumentRevisionEntity(document,1,"旧内容","old-sha",actor),actor);
        var oldLink=new WikiDocumentSkillEntity(document,oldSkill,"RELATED",0);
        var newLink=new WikiDocumentSkillEntity(document,newSkill,"RELATED",0);
        when(documents.findById(12L)).thenReturn(Optional.of(document));when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(skills.findAllById(List.of(2L))).thenReturn(List.of(newSkill));
        when(links.findByDocumentIdOrderBySortOrderAsc(12L)).thenReturn(List.of(oldLink),List.of(newLink));
        when(revisions.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("admin:identity"))));
        var service=new WikiDocumentService(documents,revisions,links,skills,mock(SkillOwnerRepository.class),
                mock(OrgTeamRepository.class),mock(OrgTeamMemberRepository.class),mock(ScopedRoleAssignmentRepository.class),
                users,mock(AuditService.class),events);

        var result=service.update(12L,new WikiDocumentService.UpdateCommand("新标题","新内容",0,List.of(2L)),9L,"test-request");

        assertThat(result.skills()).extracting(WikiDocumentService.SkillLinkView::skillKey).containsExactly("new-skill");
        assertThat(result.revisionNo()).isEqualTo(2);
        verify(links).delete(oldLink);verify(links).save(any(WikiDocumentSkillEntity.class));verify(links).flush();
        verify(events).publishEvent(new WikiKnowledgeChangedEvent(12L,WikiKnowledgeChangedEvent.Operation.UPSERT));
    }

    private static SkillEntity skill(Long id,String key) {
        var skill=mock(SkillEntity.class);
        when(skill.getId()).thenReturn(id);when(skill.getSkillKey()).thenReturn(key);when(skill.getDisplayName()).thenReturn(key);
        when(skill.getScopeType()).thenReturn("TEAM");when(skill.getTeamId()).thenReturn(7L);
        return skill;
    }
}
