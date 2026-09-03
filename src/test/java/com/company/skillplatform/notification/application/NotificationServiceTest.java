package com.company.skillplatform.notification.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.notification.infrastructure.entity.UserNotificationEntity;
import com.company.skillplatform.notification.infrastructure.repository.UserNotificationRepository;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import com.company.skillplatform.version.domain.ChangeType;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationServiceTest {
    UserNotificationRepository repository=mock(UserNotificationRepository.class);
    IamUserRoleRepository roles=mock(IamUserRoleRepository.class);
    IamUserRepository users=mock(IamUserRepository.class);
    NotificationService service;
    IamUserEntity owner;SkillVersionEntity version;

    @BeforeEach void setup(){owner=new IamUserEntity(IdentityProviderType.MOCK,"owner","owner","h","Owner",null);ReflectionTestUtils.setField(owner,"id",1L);SkillEntity skill=new SkillEntity("demo","Demo","d",null,owner);ReflectionTestUtils.setField(skill,"id",2L);version=new SkillVersionEntity(skill,null,ChangeType.INITIAL,owner);ReflectionTestUtils.setField(version,"id",3L);service=new NotificationService(repository,roles,users,Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"),ZoneOffset.UTC));}

    @Test void notifiesReviewersAndSubmitter(){when(roles.findActiveUsersByPermission("skill:review")).thenReturn(List.of(owner));when(users.findById(1L)).thenReturn(Optional.of(owner));service.reviewSubmitted(version,4L);service.reviewCompleted(version,4L,true,1L,"ok");service.reviewCompleted(version,4L,false,1L,"fix it");verify(repository,times(3)).saveAll(any());}

    @Test void readsInboxCountAndMarksOwnedNotification(){UserNotificationEntity entity=new UserNotificationEntity(owner,com.company.skillplatform.notification.domain.NotificationType.REVIEW_APPROVED,"title","content","REVIEW",4L,version);ReflectionTestUtils.setField(entity,"id",5L);when(repository.findByRecipientId(1L,Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(entity)));when(repository.findByRecipientIdAndReadAtIsNull(1L,Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(entity)));when(repository.countByRecipientIdAndReadAtIsNull(1L)).thenReturn(1L);when(repository.findByIdAndRecipientId(5L,1L)).thenReturn(Optional.of(entity));assertThat(service.inbox(1L,false,Pageable.unpaged())).hasSize(1);assertThat(service.inbox(1L,true,Pageable.unpaged())).hasSize(1);assertThat(service.unreadCount(1L)).isOne();assertThat(service.markRead(5L,1L).readAt()).isNotNull();assertThatThrownBy(()->service.markRead(6L,1L)).hasMessageContaining("Notification not found");}

    @Test void reservesVersionUpdateRecipientExtension(){service.versionUpdated(version,v->List.of(owner));verify(repository).saveAll(any());}
    @Test void publishesAndLifecycleChangesNotifyOwners(){var ownerRepository=mock(com.company.skillplatform.skill.infrastructure.repository.SkillOwnerRepository.class);service.setOwners(ownerRepository);when(ownerRepository.findBySkillId(2L)).thenReturn(List.of(new com.company.skillplatform.skill.infrastructure.entity.SkillOwnerEntity(version.getSkill(),owner,com.company.skillplatform.skill.domain.OwnerType.PRIMARY)));when(users.findById(1L)).thenReturn(Optional.of(owner));service.publishCompleted(version,8L,1L,true,null);service.publishCompleted(version,9L,1L,false,"ARTIFACT_BUILD_FAILED");service.lifecycleChanged(version,"SKILL_DEPRECATED");service.lifecycleChanged(version,"SKILL_OFFLINE");verify(repository,times(4)).saveAll(any());}
}
