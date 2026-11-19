package com.company.skillplatform.user.application;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.user.domain.RoleStatus;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.entity.*;
import com.company.skillplatform.user.infrastructure.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WikiDirectPublisherGrantTest {
    @Test void grantAddsOnlyDedicatedRoleAndRevokeRemovesOnlyItsAssignment() {
        var users=mock(IamUserRepository.class);var roles=mock(IamRoleRepository.class);
        var userRoles=mock(IamUserRoleRepository.class);var target=mock(IamUserEntity.class);
        var operator=mock(IamUserEntity.class);var direct=mock(IamRoleEntity.class);var consumer=mock(IamRoleEntity.class);
        when(users.findByIdForUpdate(9L)).thenReturn(Optional.of(target));
        when(users.findById(1L)).thenReturn(Optional.of(operator));
        when(target.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(direct.getId()).thenReturn(100L);when(direct.getStatus()).thenReturn(RoleStatus.ACTIVE);
        when(consumer.getId()).thenReturn(2L);
        when(roles.findByRoleKey("WIKI_DIRECT_PUBLISHER")).thenReturn(Optional.of(direct));
        var existing=new IamUserRoleEntity(target,consumer,operator);
        when(userRoles.findAllByUserId(9L)).thenReturn(List.of(existing));
        var service=new IdentityAdminService(users,roles,mock(IamPermissionRepository.class),userRoles,
                mock(IamRolePermissionRepository.class),mock(AuditService.class));

        service.grantWikiDirectPublisher(9L,1L,"grant");

        var saved=ArgumentCaptor.forClass(IamUserRoleEntity.class);
        verify(userRoles).save(saved.capture());
        assertThat(saved.getValue().getRole()).isSameAs(direct);
        verify(userRoles,never()).delete(existing);
        verify(userRoles,never()).deleteAllByUserId(anyLong());

        var assignment=new IamUserRoleEntity(target,direct,operator);
        when(userRoles.findAllByUserId(9L)).thenReturn(List.of(existing,assignment));
        service.revokeWikiDirectPublisher(9L,1L,"revoke");
        verify(userRoles).delete(assignment);
        verify(userRoles,never()).delete(existing);
    }
}
