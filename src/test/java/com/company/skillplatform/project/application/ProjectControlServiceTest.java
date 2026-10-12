package com.company.skillplatform.project.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.project.infrastructure.entity.VirtualProjectEntity;
import com.company.skillplatform.project.infrastructure.repository.*;
import com.company.skillplatform.user.domain.IdentityProviderType;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ProjectControlServiceTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDoesNotValidateOrConfigureFeishuRoot() {
        VirtualProjectRepository projects = mock(VirtualProjectRepository.class);
        VirtualProjectMemberRepository members = mock(VirtualProjectMemberRepository.class);
        ProjectWorkflowService workflow = mock(ProjectWorkflowService.class);
        FeishuDocumentMcpProxy feishu = mock(FeishuDocumentMcpProxy.class);
        IamUserRepository users = mock(IamUserRepository.class);
        IamUserEntity actor = new IamUserEntity(IdentityProviderType.FEISHU, "u-7", "user", "User", "user@example.com");
        when(users.findById(7L)).thenReturn(Optional.of(actor));
        when(projects.save(any(VirtualProjectEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectControlService service = new ProjectControlService(
                projects, members, mock(ProjectDocumentRepository.class), mock(ProjectDocumentRevisionRepository.class),
                mock(ProjectDocumentSourceRepository.class), users, mock(AuditService.class), new ObjectMapper(),
                mock(ScopedRoleAssignmentRepository.class), workflow);
        service.setFeishu(feishu);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                7L, "n/a", java.util.List.of(() -> "admin:identity")));

        service.create(new ProjectControlService.CreateProject("Project", "Description", null), 7L, "request-1");

        verifyNoInteractions(feishu);
        verify(workflow).initialize(any(), isNull());
    }
}
