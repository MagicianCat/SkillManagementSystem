package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.infrastructure.repository.AgentRunRepository;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.project.infrastructure.repository.DocumentAgentSessionRepository;
import java.time.Duration;
import java.time.Instant;
import com.company.skillplatform.agent.domain.AgentRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AgentRunServiceDocumentCapabilitiesTest {
    private final AgentRunService service = new AgentRunService(
            mock(AgentRunRepository.class),
            "SkillManagementJwtSigningKey-2026-AtLeast32Bytes",
            Duration.ofMinutes(5), Duration.ofHours(720),
            mock(DocumentAgentSessionRepository.class));

    @Test
    void documentJobMaySearchAndReadOnlyItsSelectedWikiContext() {
        var run = service.issueDocumentJobRun(1L, "project", null,
                "requirement-analysis/v1", "session", "job").run();

        assertDoesNotThrow(() -> service.requireCapability(run, "wiki.search"));
        assertDoesNotThrow(() -> service.requireCapability(run, "wiki.read"));
    }

    @Test
    void documentJobCannotUseKnowledgeSearchOutsideItsSelectedWikiContext() {
        var run = service.issueDocumentJobRun(1L, "project", null,
                "requirement-analysis/v1", "session", "job").run();

        assertThrows(BusinessException.class,
                () -> service.requireCapability(run, "knowledge.search"));
    }

    @Test
    void workflowRunMayUseOnlyReadOnlyCodeGraphCapabilities() {
        var run = new AgentRun("workflow-31-agent-99", 1L, "developer", null, null,
                Instant.now().plusSeconds(60), "ACTIVE", "PROJECT_MEMBER", "project", null, null);

        for (String capability : java.util.List.of(
                "code_graph.overview", "code_graph.query", "code_graph.context",
                "code_graph.impact", "code_graph.trace", "code_graph.route_map")) {
            assertDoesNotThrow(() -> service.requireCapability(run, capability));
        }
        assertThrows(BusinessException.class,
                () -> service.requireCapability(run, "code_graph.admin"));
    }

    @Test
    void documentAgentCannotQueryCodeGraph() {
        var run = service.issueDocumentJobRun(1L, "project", null,
                "requirement-analysis/v1", "session", "job").run();

        assertThrows(BusinessException.class,
                () -> service.requireCapability(run, "code_graph.query"));
    }
}
