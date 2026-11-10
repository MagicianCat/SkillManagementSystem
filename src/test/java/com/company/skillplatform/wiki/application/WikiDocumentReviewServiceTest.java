package com.company.skillplatform.wiki.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.OrgTeamRepository;
import com.company.skillplatform.user.infrastructure.repository.ScopedRoleAssignmentRepository;
import com.company.skillplatform.wiki.domain.WikiReviewStatus;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentReviewEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentReviewRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class WikiDocumentReviewServiceTest {
    @Test void approvalPromotesEveryDistinctTeamSkillWithTheWiki() {
        var reviews=mock(WikiDocumentReviewRepository.class);var documents=mock(WikiDocumentRepository.class);
        var skills=mock(SkillRepository.class);var links=mock(WikiDocumentSkillRepository.class);
        var roles=mock(ScopedRoleAssignmentRepository.class);var users=mock(IamUserRepository.class);
        var members=mock(com.company.skillplatform.user.infrastructure.repository.OrgTeamMemberRepository.class);
        var actor=mock(IamUserEntity.class);var document=new WikiDocumentEntity("根知识",7L,false,actor);
        ReflectionTestUtils.setField(document,"id",90L);
        var revision=new WikiDocumentRevisionEntity(document,1,"正文","sha",actor);
        ReflectionTestUtils.setField(revision,"id",91L);document.setCurrentRevision(revision,actor);
        var review=new WikiDocumentReviewEntity(document,revision,1,actor,"申请",Instant.EPOCH);
        ReflectionTestUtils.setField(review,"id",92L);review.captureTree(7L,"fingerprint",1,2);
        var first=mock(com.company.skillplatform.skill.infrastructure.entity.SkillEntity.class);
        var second=mock(com.company.skillplatform.skill.infrastructure.entity.SkillEntity.class);
        var firstItem=new WikiPromotionStore.SkillItem(201L,"first","TEAM",7L,301L,first);
        var secondItem=new WikiPromotionStore.SkillItem(202L,"second","TEAM",7L,302L,second);
        var tree=new WikiPromotionStore.Tree(90L,7L,List.of(new WikiPromotionStore.Item(90L,null,"根知识",91L,1,"sha",List.of(201L,202L),document)),List.of(firstItem,secondItem),"fingerprint");
        var promotion=mock(WikiPromotionStore.class);
        when(reviews.findByIdForUpdate(92L)).thenReturn(Optional.of(review));
        when(documents.findByIdForUpdate(90L)).thenReturn(Optional.of(document));
        when(promotion.reviewItems(92L)).thenReturn(List.of(new WikiPromotionStore.ReviewWikiItem(90L,"根知识",null,91L,1)));
        when(promotion.reviewSkills(92L)).thenReturn(List.of(new WikiPromotionStore.ReviewSkillItem(201L,"first","First","TEAM",301L),new WikiPromotionStore.ReviewSkillItem(202L,"second","Second","TEAM",302L)));
        when(promotion.collect(document)).thenReturn(tree);
        when(skills.findBySkillKeyForUpdate("first")).thenReturn(Optional.of(first));
        when(skills.findBySkillKeyForUpdate("second")).thenReturn(Optional.of(second));
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        var team=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity.class);
        var member=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity.class);
        when(team.getId()).thenReturn(7L);when(team.getStatus()).thenReturn("ACTIVE");
        when(member.getTeam()).thenReturn(team);when(member.getStatus()).thenReturn("ACTIVE");
        when(members.findByUserId(9L)).thenReturn(List.of(member));
        when(roles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(9L,"TEAM_ADMIN","TEAM",7L)).thenReturn(Optional.of(mock(com.company.skillplatform.user.infrastructure.entity.ScopedRoleAssignmentEntity.class)));
        var service=new WikiDocumentReviewService(reviews,documents,links,mock(OrgTeamRepository.class),skills,roles,users,
                mock(ApplicationEventPublisher.class),mock(AuditService.class),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        service.setMembers(members);service.setPromotionStore(promotion);

        var result=service.approve(92L,"通过",9L,"multi-skill");

        assertThat(result.status()).isEqualTo(WikiReviewStatus.APPROVED);
        assertThat(document.isPlatformVisible()).isTrue();
        verify(first).promoteToPlatform(actor);verify(second).promoteToPlatform(actor);
        verify(promotion).publish(92L,tree,9L,Instant.EPOCH);
    }
    @Test void submitsAndApprovesTeamGuideWithoutSkills() {
        var reviews=mock(WikiDocumentReviewRepository.class);var documents=mock(WikiDocumentRepository.class);var skills=mock(SkillRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var events=mock(ApplicationEventPublisher.class);
        var roles=mock(ScopedRoleAssignmentRepository.class);var users=mock(IamUserRepository.class);
        var actor=mock(IamUserEntity.class);var document=new WikiDocumentEntity("独立 Wiki",7L,false,actor);
        ReflectionTestUtils.setField(document,"id",81L);
        var revision=new WikiDocumentRevisionEntity(document,1,"# 独立知识","sha",actor);
        ReflectionTestUtils.setField(revision,"id",82L);document.setCurrentRevision(revision,actor);
        when(documents.findById(81L)).thenReturn(Optional.of(document));
        when(documents.findByIdForUpdate(81L)).thenReturn(Optional.of(document));
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(roles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(9L,"TEAM_ADMIN","TEAM",7L)).thenReturn(Optional.of(mock(com.company.skillplatform.user.infrastructure.entity.ScopedRoleAssignmentEntity.class)));
        when(reviews.existsByDocumentIdAndStatus(81L,WikiReviewStatus.PENDING)).thenReturn(false);
        when(reviews.countByDocumentId(81L)).thenReturn(0L);
        when(links.findByDocumentIdOrderBySortOrderAsc(81L)).thenReturn(List.of());
        var savedReview=new java.util.concurrent.atomic.AtomicReference<WikiDocumentReviewEntity>();
        when(reviews.save(any())).thenAnswer(invocation->{var review=(WikiDocumentReviewEntity)invocation.getArgument(0);ReflectionTestUtils.setField(review,"id",55L);savedReview.set(review);return review;});
        when(reviews.findById(55L)).thenAnswer(ignored->Optional.ofNullable(savedReview.get()));
        when(reviews.findByIdForUpdate(55L)).thenAnswer(ignored->Optional.ofNullable(savedReview.get()));
        when(documents.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        var members=mock(com.company.skillplatform.user.infrastructure.repository.OrgTeamMemberRepository.class);
        var team=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity.class);
        var membership=mock(com.company.skillplatform.user.infrastructure.entity.OrgTeamMemberEntity.class);
        when(team.getId()).thenReturn(7L);when(team.getStatus()).thenReturn("ACTIVE");
        when(membership.getTeam()).thenReturn(team);when(membership.getStatus()).thenReturn("ACTIVE");
        when(members.findByUserId(9L)).thenReturn(List.of(membership));
        var promotion=mock(WikiPromotionStore.class);
        var tree=new WikiPromotionStore.Tree(81L,7L,List.of(new WikiPromotionStore.Item(81L,null,"独立 Wiki",82L,1,"sha",List.of(),document)),List.of(),"fingerprint");
        when(promotion.collect(document)).thenReturn(tree);
        when(promotion.reviewItems(55L)).thenReturn(List.of(new WikiPromotionStore.ReviewWikiItem(81L,"独立 Wiki",null,82L,1)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("skill:edit"),new SimpleGrantedAuthority("wiki:review"))));
        try {
            var service=new WikiDocumentReviewService(reviews,documents,links,mock(OrgTeamRepository.class),skills,
                    roles,users,events,mock(AuditService.class),Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),ZoneOffset.UTC));
            service.setMembers(members);service.setPromotionStore(promotion);
            var submitted=service.submit(81L,0,"请审核",9L,"submit-request");
            assertThat(submitted.skills()).isEmpty();assertThat(submitted.status()).isEqualTo(WikiReviewStatus.PENDING);
            var approved=service.approve(55L,"通过",9L,"approve-request");
            assertThat(approved.skills()).isEmpty();assertThat(approved.status()).isEqualTo(WikiReviewStatus.APPROVED);
            assertThat(document.isPlatformVisible()).isTrue();
            verifyNoInteractions(links);
            verifyNoInteractions(skills);
            verify(events).publishEvent(new WikiKnowledgeChangedEvent(81L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
