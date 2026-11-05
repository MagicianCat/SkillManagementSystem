package com.company.skillplatform.agentworkflow.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectAgentConfigurationProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsCurrentSingleResultCodeConst() throws Exception {
        var schema = json.readTree("{\"properties\":{\"resultCode\":{\"const\":\"DOCUMENT_CREATED\"}}}");
        assertEquals(Set.of("DOCUMENT_CREATED"), ProjectAgentConfigurationService.resultCodes(schema));
    }

    @Test
    void acceptsReviewerResultCodeEnum() throws Exception {
        var schema = json.readTree("{\"properties\":{\"resultCode\":{\"enum\":[\"APPROVED\",\"REVISION_REQUIRED\"]}}}");
        assertEquals(Set.of("APPROVED", "REVISION_REQUIRED"), ProjectAgentConfigurationService.resultCodes(schema));
    }
}
