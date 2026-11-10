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

    @Test void ordinaryActiveMemberCreatesUnlinkedTeamWiki() {
        var documents=mock(WikiDocumentRepository.class);var revisions=mock(WikiDocumentRevisionRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var users=mock(IamUserRepository.class);
        var members=mock(OrgTeamMemberRepository.class);var actor=mock(IamUserEntity.class);
        var team=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity.class);
        var membership=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity.class);
        when(team.getId()).thenReturn(7L);when(team.getStatus()).thenReturn("ACTIVE");
        when(membership.getTeam()).thenReturn(team);when(membership.getStatus()).thenReturn("ACTIVE");
        when(members.findByUserId(9L)).thenReturn(List.of(membership));
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(documents.save(any())).thenAnswer(invocation->{var document=(WikiDocumentEntity)invocation.getArgument(0);ReflectionTestUtils.setField(document,"id",91L);return document;});
        when(revisions.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(links.findByDocumentIdOrderBySortOrderAsc(91L)).thenReturn(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(
                new SimpleGrantedAuthority("wiki:create"),new SimpleGrantedAuthority("wiki:edit"))));
        var service=new WikiDocumentService(documents,revisions,links,mock(SkillRepository.class),mock(SkillOwnerRepository.class),
                mock(OrgTeamRepository.class),members,mock(ScopedRoleAssignmentRepository.class),users,mock(AuditService.class),mock(ApplicationEventPublisher.class));

        var result=service.create(new WikiDocumentService.CreateCommand("团队知识",7L,List.of(),"正文"),9L,"member-create");

        assertThat(result.id()).isEqualTo(91L);
        assertThat(result.canEdit()).isTrue();
        assertThat(result.skills()).isEmpty();
    }

    @Test void memberOfTwoTeamsCannotLinkOtherTeamsPrivateSkill() {
        var documents=mock(WikiDocumentRepository.class);var users=mock(IamUserRepository.class);
        var members=mock(OrgTeamMemberRepository.class);var skills=mock(SkillRepository.class);
        var actor=mock(IamUserEntity.class);when(users.findById(9L)).thenReturn(Optional.of(actor));
        var teamA=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity.class);
        var teamB=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity.class);
        when(teamA.getId()).thenReturn(7L);when(teamA.getStatus()).thenReturn("ACTIVE");
        when(teamB.getId()).thenReturn(8L);when(teamB.getStatus()).thenReturn("ACTIVE");
        var memberA=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity.class);
        var memberB=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity.class);
        when(memberA.getTeam()).thenReturn(teamA);when(memberA.getStatus()).thenReturn("ACTIVE");
        when(memberB.getTeam()).thenReturn(teamB);when(memberB.getStatus()).thenReturn("ACTIVE");
        when(members.findByUserId(9L)).thenReturn(List.of(memberA,memberB));
        var otherTeamSkill=mock(SkillEntity.class);
        when(otherTeamSkill.getId()).thenReturn(4L);
        when(otherTeamSkill.getStatus()).thenReturn(com.company.skillplatform.skill.domain.SkillStatus.ACTIVE);
        when(otherTeamSkill.getScopeType()).thenReturn("TEAM");when(otherTeamSkill.getTeamId()).thenReturn(8L);
        when(skills.findAllById(List.of(4L))).thenReturn(List.of(otherTeamSkill));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(
                new SimpleGrantedAuthority("wiki:create"),new SimpleGrantedAuthority("wiki:edit"))));
        var service=new WikiDocumentService(documents,mock(WikiDocumentRevisionRepository.class),mock(WikiDocumentSkillRepository.class),skills,
                mock(SkillOwnerRepository.class),mock(OrgTeamRepository.class),members,mock(ScopedRoleAssignmentRepository.class),
                users,mock(AuditService.class),mock(ApplicationEventPublisher.class));

        assertThatThrownBy(()->service.create(new WikiDocumentService.CreateCommand("知识",7L,List.of(4L),"正文"),9L,"cross-team"))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getCode()).isEqualTo("WIKI_CROSS_TEAM_SKILL"));
        verify(documents,never()).save(any());
    }

    @Test void publishedTeamWikiCannotBeEditedBeforePlatformSnapshotExists() {
        var documents=mock(WikiDocumentRepository.class);
        var actor=mock(IamUserEntity.class);
        var document=new WikiDocumentEntity("已公开",7L,true,actor);
        ReflectionTestUtils.setField(document,"id",99L);
        when(documents.findById(99L)).thenReturn(Optional.of(document));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("admin:identity"))));
        var service=new WikiDocumentService(documents,mock(WikiDocumentRevisionRepository.class),mock(WikiDocumentSkillRepository.class),
                mock(SkillRepository.class),mock(SkillOwnerRepository.class),mock(OrgTeamRepository.class),
                mock(OrgTeamMemberRepository.class),mock(ScopedRoleAssignmentRepository.class),mock(IamUserRepository.class),
                mock(AuditService.class),mock(ApplicationEventPublisher.class));

        assertThatThrownBy(()->service.update(99L,new WikiDocumentService.UpdateCommand("新标题","新正文",0,List.of()),9L,"published-edit"))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getCode()).isEqualTo("WIKI_PLATFORM_SNAPSHOT_REQUIRED"));
    }

    @Test void teamEditOfPublishedWikiDoesNotChangeOutsidersApprovedView() {
        var documents=mock(WikiDocumentRepository.class);var revisions=mock(WikiDocumentRevisionRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var members=mock(OrgTeamMemberRepository.class);
        var users=mock(IamUserRepository.class);var actor=mock(IamUserEntity.class);
        var document=new WikiDocumentEntity("批准标题",7L,true,actor);
        ReflectionTestUtils.setField(document,"id",101L);
        var oldRevision=new WikiDocumentRevisionEntity(document,1,"批准正文","sha-old",actor);
        document.setCurrentRevision(oldRevision,actor);
        when(documents.findById(101L)).thenReturn(Optional.of(document));
        when(documents.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(revisions.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(links.findByDocumentIdOrderBySortOrderAsc(101L)).thenReturn(List.of());
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        var team=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity.class);
        var membership=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity.class);
        when(team.getId()).thenReturn(7L);when(team.getStatus()).thenReturn("ACTIVE");
        when(membership.getTeam()).thenReturn(team);when(membership.getStatus()).thenReturn("ACTIVE");
        when(members.findByUserId(9L)).thenReturn(List.of(membership));
        when(members.findByUserId(10L)).thenReturn(List.of());
        var promotion=mock(WikiPromotionStore.class);
        when(promotion.platform(101L)).thenReturn(new WikiPromotionStore.PlatformSnapshot(101L,44L,"批准标题",null,1,"批准正文"));
        when(promotion.platformSkills(101L)).thenReturn(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("wiki:edit"))));
        var service=new WikiDocumentService(documents,revisions,links,mock(SkillRepository.class),mock(SkillOwnerRepository.class),
                mock(OrgTeamRepository.class),members,mock(ScopedRoleAssignmentRepository.class),users,mock(AuditService.class),mock(ApplicationEventPublisher.class));
        service.setPromotionStore(promotion);

        var teamView=service.update(101L,new WikiDocumentService.UpdateCommand("团队新标题","团队新正文",0,List.of()),9L,"edit");
        assertThat(teamView.title()).isEqualTo("团队新标题");
        assertThat(teamView.markdownContent()).isEqualTo("团队新正文");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(10L,null,List.of(new SimpleGrantedAuthority("skill:browse"))));
        var publicView=service.get(101L,10L);
        assertThat(publicView.title()).isEqualTo("批准标题");
        assertThat(publicView.markdownContent()).isEqualTo("批准正文");
        assertThat(publicView.canEdit()).isFalse();
    }

    @Test void archivingPendingWikiReleasesPromotionLocks() {
        var documents=mock(WikiDocumentRepository.class);
        var reviews=mock(WikiDocumentReviewRepository.class);
        var promotion=mock(WikiPromotionStore.class);
        var users=mock(IamUserRepository.class);
        var actor=mock(IamUserEntity.class);
        var document=new WikiDocumentEntity("待审知识",7L,false,actor);
        ReflectionTestUtils.setField(document,"id",120L);
        var revision=new WikiDocumentRevisionEntity(document,1,"正文","sha",actor);
        document.setCurrentRevision(revision,actor);
        var review=new WikiDocumentReviewEntity(document,revision,1,actor,"提交",java.time.Instant.EPOCH);
        ReflectionTestUtils.setField(review,"id",30L);
        when(documents.findById(120L)).thenReturn(Optional.of(document));
        when(documents.findByParentIdAndStatusOrderByIdAsc(120L,"ACTIVE")).thenReturn(List.of());
        when(reviews.findByDocumentIdAndStatus(120L,WikiReviewStatus.PENDING)).thenReturn(Optional.of(review));
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("admin:identity"))));
        var service=new WikiDocumentService(documents,mock(WikiDocumentRevisionRepository.class),mock(WikiDocumentSkillRepository.class),
                mock(SkillRepository.class),mock(SkillOwnerRepository.class),mock(OrgTeamRepository.class),
                mock(OrgTeamMemberRepository.class),mock(ScopedRoleAssignmentRepository.class),users,
                mock(AuditService.class),mock(ApplicationEventPublisher.class));
        service.setReviewRepository(reviews);service.setPromotionStore(promotion);

        service.archive(120L,9L,"archive-test");

        assertThat(review.getStatus()).isEqualTo(WikiReviewStatus.CANCELLED);
        verify(promotion).release(30L);
    }

    @Test void visibleDescendantsFiltersPrivateBranchesAndReportsTruncation() {
        var documents=mock(WikiDocumentRepository.class);
        var root=wikiNode(10L,"目录",true, null);
        var publicChild=wikiNode(11L,"公开子文档",true, null);
        var privateChild=wikiNode(12L,"其他团队私有文档",false, 7L);
        var grandchild=wikiNode(13L,"公开孙文档",true, null);
        when(documents.findById(10L)).thenReturn(Optional.of(root));
        when(documents.findByParentIdAndStatusOrderByIdAsc(10L,"ACTIVE")).thenReturn(List.of(publicChild,privateChild));
        when(documents.findByParentIdAndStatusOrderByIdAsc(11L,"ACTIVE")).thenReturn(List.of(grandchild));
        var service=new WikiDocumentService(documents,mock(WikiDocumentRevisionRepository.class),mock(WikiDocumentSkillRepository.class),
                mock(SkillRepository.class),mock(SkillOwnerRepository.class),mock(OrgTeamRepository.class),
                mock(OrgTeamMemberRepository.class),mock(ScopedRoleAssignmentRepository.class),mock(IamUserRepository.class),
                mock(AuditService.class),mock(ApplicationEventPublisher.class));

        var directory=service.visibleDescendants(10L,9L,false,3,20);
        assertThat(directory.items()).extracting(WikiDocumentService.DescendantSummary::documentId).containsExactly(11L,13L);
        assertThat(directory.items()).extracting(WikiDocumentService.DescendantSummary::depth).containsExactly(1,2);
        assertThat(directory.truncated()).isFalse();
        verify(documents,never()).findByParentIdAndStatusOrderByIdAsc(12L,"ACTIVE");

        var limited=service.visibleDescendants(10L,9L,false,3,1);
        assertThat(limited.items()).extracting(WikiDocumentService.DescendantSummary::documentId).containsExactly(11L);
        assertThat(limited.truncated()).isTrue();
    }

    @Test void publicOnlyDirectoryExcludesPrivateChildrenEvenForAdministrator() {
        var documents=mock(WikiDocumentRepository.class);
        var root=wikiNode(10L,"目录",true,null);
        var publicChild=wikiNode(11L,"公开文档",true,null);
        var privateChild=wikiNode(12L,"私有文档",false,7L);
        when(documents.findById(10L)).thenReturn(Optional.of(root));
        when(documents.findByParentIdAndStatusOrderByIdAsc(10L,"ACTIVE")).thenReturn(List.of(
                publicChild,privateChild));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,
                List.of(new SimpleGrantedAuthority("admin:identity"))));
        var service=new WikiDocumentService(documents,mock(WikiDocumentRevisionRepository.class),mock(WikiDocumentSkillRepository.class),
                mock(SkillRepository.class),mock(SkillOwnerRepository.class),mock(OrgTeamRepository.class),
                mock(OrgTeamMemberRepository.class),mock(ScopedRoleAssignmentRepository.class),mock(IamUserRepository.class),
                mock(AuditService.class),mock(ApplicationEventPublisher.class));

        var directory=service.visibleDescendants(10L,9L,true,1,20);
        assertThat(directory.items()).extracting(WikiDocumentService.DescendantSummary::documentId).containsExactly(11L);
        assertThat(directory.truncated()).isFalse();
    }

    private static WikiDocumentEntity wikiNode(Long id,String title,boolean publicVisible,Long teamId) {
        var document=mock(WikiDocumentEntity.class);
        when(document.getId()).thenReturn(id);when(document.getTitle()).thenReturn(title);
        when(document.getStatus()).thenReturn("ACTIVE");when(document.isPlatformVisible()).thenReturn(publicVisible);
        when(document.getTeamId()).thenReturn(teamId);
        return document;
    }

    @Test void updatingTeamWikiReplacesLinkedSkillsAndPublishesReindexEvent() {
        var documents=mock(WikiDocumentRepository.class);var revisions=mock(WikiDocumentRevisionRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var skills=mock(SkillRepository.class);
        var users=mock(IamUserRepository.class);var events=mock(ApplicationEventPublisher.class);
        var actor=mock(IamUserEntity.class);
        var oldSkill=skill(1L,"old-skill");var newSkill=skill(2L,"new-skill");
        var document=new WikiDocumentEntity("旧标题",7L,false,actor);
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

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("admin:identity"))));
        var result=service.create(new WikiDocumentService.CreateCommand("独立知识文档",7L,List.of(),"# 团队接口规范"),9L,"request-no-skill");

        assertThat(result.id()).isEqualTo(81L);
        assertThat(result.skills()).isEmpty();
        assertThat(result.markdownContent()).contains("团队接口规范");
        verify(links,never()).save(any());
        verify(events).publishEvent(new WikiKnowledgeChangedEvent(81L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        verifyNoInteractions(skills);
    }

    @Test void rejectsMemberCreatingSystemWiki() {
        var users=mock(IamUserRepository.class);var actor=mock(IamUserEntity.class);when(users.findById(9L)).thenReturn(Optional.of(actor));
        var service=new WikiDocumentService(mock(WikiDocumentRepository.class),mock(WikiDocumentRevisionRepository.class),
                mock(WikiDocumentSkillRepository.class),mock(SkillRepository.class),mock(SkillOwnerRepository.class),
                mock(OrgTeamRepository.class),mock(OrgTeamMemberRepository.class),mock(ScopedRoleAssignmentRepository.class),
                users,mock(AuditService.class),mock(ApplicationEventPublisher.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("wiki:create"))));
        assertThatThrownBy(()->service.create(new WikiDocumentService.CreateCommand("Wiki",null,List.of(),"content"),9L,"request-wiki"))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getCode()).isEqualTo("WIKI_TEAM_MEMBER_REQUIRED"));
    }

    @Test void clearingTeamGuideSkillsRemovesLinksAndReindexes() {
        var documents=mock(WikiDocumentRepository.class);var revisions=mock(WikiDocumentRevisionRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var events=mock(ApplicationEventPublisher.class);
        var actor=mock(IamUserEntity.class);var document=new WikiDocumentEntity("知识文档",7L,false,actor);
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
        when(skill.getStatus()).thenReturn(com.company.skillplatform.skill.domain.SkillStatus.ACTIVE);
        return skill;
    }
}
