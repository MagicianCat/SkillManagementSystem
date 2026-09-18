package com.company.skillplatform.agentworkflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

@Component
public class StageCompletionEvaluator {
    public boolean requirementApproved(JsonNode result, Long latestRevisionId) {
        if (result == null || latestRevisionId == null || !"SUCCESS".equals(result.path("executionStatus").asText())
                || !"APPROVED".equals(result.path("resultCode").asText())) return false;
        JsonNode reviewed = result.path("reviewedArtifactRevisionIds");
        boolean latest = reviewed.isArray() && StreamSupport.stream(reviewed.spliterator(), false)
                .anyMatch(value -> value.canConvertToLong() && value.asLong() == latestRevisionId);
        return latest && result.path("blockingIssues").isArray() && result.path("blockingIssues").isEmpty();
    }
}
