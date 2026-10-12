package com.company.skillplatform.agentworkflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class StageCompletionEvaluator {
    public boolean reviewApproved(JsonNode result, Long backendPinnedRevisionId) {
        if (result == null || backendPinnedRevisionId == null || !"APPROVED".equals(result.path("resultCode").asText()))
            return false;
        return !result.has("blockingIssues") || (result.path("blockingIssues").isArray()
                && result.path("blockingIssues").isEmpty());
    }
}
