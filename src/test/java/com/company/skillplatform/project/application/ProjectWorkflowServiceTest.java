package com.company.skillplatform.project.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.skillplatform.project.infrastructure.repository.VirtualProjectMemberRepository;
import com.company.skillplatform.project.infrastructure.repository.VirtualProjectRepository;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProjectWorkflowServiceTest {
    @Test
    void initializesFourAvailableAndFourFutureStagesWithFixedDependencies() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        ProjectWorkflowService service=new ProjectWorkflowService(jdbc,mock(VirtualProjectRepository.class),mock(VirtualProjectMemberRepository.class),mock(ObjectStoragePort.class));

        service.initialize(7L,List.of("REQUIREMENT","PRD","ARCHITECTURE"));

        verify(jdbc,times(8)).update(startsWith("insert into project_stage("),any(Object[].class));
        verify(jdbc,times(3)).update(startsWith("insert into project_stage_dependency"),any(Object[].class));
    }

    @Test
    void exposesOnlySupportedFunctionalRoles() {
        org.junit.jupiter.api.Assertions.assertEquals(8,ProjectWorkflowService.FUNCTIONAL_ROLES.size());
        org.junit.jupiter.api.Assertions.assertTrue(ProjectWorkflowService.FUNCTIONAL_ROLES.contains("REQUIREMENT_REVIEWER"));
        org.junit.jupiter.api.Assertions.assertFalse(ProjectWorkflowService.FUNCTIONAL_ROLES.contains("OWNER"));
    }
}
