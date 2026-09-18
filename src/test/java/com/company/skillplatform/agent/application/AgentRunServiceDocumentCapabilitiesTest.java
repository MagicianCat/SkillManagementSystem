package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.infrastructure.repository.AgentRunRepository;
import com.company.skillplatform.project.infrastructure.repository.DocumentAgentSessionRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
