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
    @Test void submitsAndApprovesTeamGuideWithoutSkills() {
        var reviews=mock(WikiDocumentReviewRepository.class);var documents=mock(WikiDocumentRepository.class);var skills=mock(SkillRepository.class);
        var links=mock(WikiDocumentSkillRepository.class);var events=mock(ApplicationEventPublisher.class);
        var roles=mock(ScopedRoleAssignmentRepository.class);var users=mock(IamUserRepository.class);
        var actor=mock(IamUserEntity.class);var document=new WikiDocumentEntity("独立 Wiki","SKILL_GUIDE",7L,false,actor);
        ReflectionTestUtils.setField(document,"id",81L);
        var revision=new WikiDocumentRevisionEntity(document,1,"# 独立知识","sha",actor);
        ReflectionTestUtils.setField(revision,"id",82L);document.setCurrentRevision(revision,actor);
        when(documents.findById(81L)).thenReturn(Optional.of(document));
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(roles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(9L,"TEAM_ADMIN","TEAM",7L)).thenReturn(Optional.of(mock(com.company.skillplatform.user.infrastructure.entity.ScopedRoleAssignmentEntity.class)));
        when(reviews.existsByDocumentIdAndStatus(81L,WikiReviewStatus.PENDING)).thenReturn(false);
        when(reviews.countByDocumentId(81L)).thenReturn(0L);
        when(links.findByDocumentIdOrderBySortOrderAsc(81L)).thenReturn(List.of());
        var savedReview=new java.util.concurrent.atomic.AtomicReference<WikiDocumentReviewEntity>();
        when(reviews.save(any())).thenAnswer(invocation->{var review=(WikiDocumentReviewEntity)invocation.getArgument(0);ReflectionTestUtils.setField(review,"id",55L);savedReview.set(review);return review;});
        when(reviews.findById(55L)).thenAnswer(ignored->Optional.ofNullable(savedReview.get()));
        when(documents.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(9L,null,List.of(new SimpleGrantedAuthority("skill:edit"),new SimpleGrantedAuthority("wiki:review"))));
        try {
            var service=new WikiDocumentReviewService(reviews,documents,links,mock(OrgTeamRepository.class),skills,
                    roles,users,events,mock(AuditService.class),Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"),ZoneOffset.UTC));
            var submitted=service.submit(81L,0,"请审核",9L,"submit-request");
            assertThat(submitted.skills()).isEmpty();assertThat(submitted.status()).isEqualTo(WikiReviewStatus.PENDING);
            var approved=service.approve(55L,"通过",9L,"approve-request");
            assertThat(approved.skills()).isEmpty();assertThat(approved.status()).isEqualTo(WikiReviewStatus.APPROVED);
            assertThat(document.isPlatformVisible()).isTrue();
            verify(links,atLeastOnce()).findByDocumentIdOrderBySortOrderAsc(81L);
            verifyNoInteractions(skills);
            verify(events).publishEvent(new WikiKnowledgeChangedEvent(81L,WikiKnowledgeChangedEvent.Operation.UPSERT));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
