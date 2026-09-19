package com.company.skillplatform.agentworkflow.application;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowE2EContractTest {
    @Test
    void startCommandFreezesInitialRequestAndContext() {
        var command = new AgentWorkflowService.RunCommand("design-document-flow", 1,
                Map.of("REQUIREMENT/clarifier", 1L), "建设一个内部 Skill 平台", "[{\"type\":\"PROJECT_DOCUMENT\",\"revisionId\":101}]");
        assertEquals("建设一个内部 Skill 平台", command.initialRequest());
        assertTrue(command.contextSnapshotJson().contains("revisionId"));
    }
}
