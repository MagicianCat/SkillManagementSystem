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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.wiki.domain.WikiReviewStatus;

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

    @Test void createsTeamGuideWithoutSkillsAndPublishesIndexEvent() {
        var documents=mock(WikiDocumentRepository.class);var revisions=mock(WikiDocumentRevisionRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var skills=mock(SkillRepository.class);
        var users=mock(IamUserRepository.class);var events=mock(ApplicationEventPublisher.class);
        var actor=mock(IamUserEntity.class);var roles=mock(ScopedRoleAssignmentRepository.class);
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(roles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(9L,"TEAM_ADMIN","TEAM",7L)).thenReturn(Optional.of(mock(com.company.skillplatform.user.infrastructure.entity.ScopedRoleAssignmentEntity.class)));
        when(documents.save(any())).thenAnswer(invocation->{var document=(WikiDocumentEntity)invocation.getArgument(0);ReflectionTestUtils.setField(document,"id",81L);return document;});
        when(revisions.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(links.findByDocumentIdOrderBySortOrderAsc(81L)).thenReturn(List.of());
        var reviewRepository=mock(WikiDocumentReviewRepository.class);
        when(reviewRepository.findByDocumentIdAndStatus(81L,WikiReviewStatus.PENDING)).thenReturn(Optional.empty());
        var service=new WikiDocumentService(documents,revisions,links,skills,mock(SkillOwnerRepository.class),
                mock(OrgTeamRepository.class),mock(OrgTeamMemberRepository.class),roles,users,mock(AuditService.class),events);
        service.setReviewRepository(reviewRepository);

        var result=service.create(new WikiDocumentService.CreateCommand("独立知识文档","SKILL_GUIDE",7L,List.of(),"# 团队接口规范"),9L,"request-no-skill");

        assertThat(result.id()).isEqualTo(81L);
        assertThat(result.skills()).isEmpty();
        assertThat(result.markdownContent()).contains("团队接口规范");
        verify(links,never()).save(any());
        verify(events).publishEvent(new WikiKnowledgeChangedEvent(81L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        verifyNoInteractions(skills);
    }

    @Test void rejectsREADMEWithoutExactlyOneSkill() {
        var users=mock(IamUserRepository.class);var actor=mock(IamUserEntity.class);when(users.findById(9L)).thenReturn(Optional.of(actor));
        var service=new WikiDocumentService(mock(WikiDocumentRepository.class),mock(WikiDocumentRevisionRepository.class),
                mock(WikiDocumentSkillRepository.class),mock(SkillRepository.class),mock(SkillOwnerRepository.class),
                mock(OrgTeamRepository.class),mock(OrgTeamMemberRepository.class),mock(ScopedRoleAssignmentRepository.class),
                users,mock(AuditService.class),mock(ApplicationEventPublisher.class));
        assertThatThrownBy(()->service.create(new WikiDocumentService.CreateCommand("README","SKILL_README",null,List.of(),"content"),9L,"request-readme"))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getCode()).isEqualTo("WIKI_README_SINGLE_SKILL_REQUIRED"));
    }

    @Test void clearingTeamGuideSkillsRemovesLinksAndReindexes() {
        var documents=mock(WikiDocumentRepository.class);var revisions=mock(WikiDocumentRevisionRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var events=mock(ApplicationEventPublisher.class);
        var actor=mock(IamUserEntity.class);var document=new WikiDocumentEntity("知识文档","SKILL_GUIDE",7L,false,actor);
        ReflectionTestUtils.setField(document,"id",82L);document.setCurrentRevision(new WikiDocumentRevisionEntity(document,1,"旧内容","sha",actor),actor);
        var link=new WikiDocumentSkillEntity(document,skill(1L,"linked-skill"),"RELATED",0);
        when(documents.findById(82L)).thenReturn(Optional.of(document));when(links.findByDocumentIdOrderBySortOrderAsc(82L)).thenReturn(List.of(link),List.of());
        when(revisions.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(documents.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        var roles=mock(ScopedRoleAssignmentRepository.class);when(roles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(9L,"TEAM_ADMIN","TEAM",7L)).thenReturn(Optional.of(mock(com.company.skillplatform.user.infrastructure.entity.ScopedRoleAssignmentEntity.class)));
        var users=mock(IamUserRepository.class);when(users.findById(9L)).thenReturn(Optional.of(actor));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("admin:identity"))));
        var service=new WikiDocumentService(documents,revisions,links,mock(SkillRepository.class),mock(SkillOwnerRepository.class),
                mock(OrgTeamRepository.class),mock(OrgTeamMemberRepository.class),roles,users,mock(AuditService.class),events);

        var result=service.update(82L,new WikiDocumentService.UpdateCommand("知识文档","新内容",0,List.of()),9L,"request-clear-links");

        assertThat(result.skills()).isEmpty();verify(links).delete(link);verify(links).flush();
        verify(events).publishEvent(new WikiKnowledgeChangedEvent(82L,WikiKnowledgeChangedEvent.Operation.UPSERT));
    }

    private static SkillEntity skill(Long id,String key) {
        var skill=mock(SkillEntity.class);
        when(skill.getId()).thenReturn(id);when(skill.getSkillKey()).thenReturn(key);when(skill.getDisplayName()).thenReturn(key);
        when(skill.getScopeType()).thenReturn("TEAM");when(skill.getTeamId()).thenReturn(7L);
        return skill;
    }
}
