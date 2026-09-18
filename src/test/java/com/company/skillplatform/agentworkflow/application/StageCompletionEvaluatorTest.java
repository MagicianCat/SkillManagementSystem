package com.company.skillplatform.agentworkflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StageCompletionEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StageCompletionEvaluator evaluator = new StageCompletionEvaluator();

    @Test void approvesOnlyTheLatestReviewedRevisionWithoutBlockers() throws Exception {
        var result=json.readTree("{\"executionStatus\":\"SUCCESS\",\"resultCode\":\"APPROVED\",\"reviewedArtifactRevisionIds\":[12],\"blockingIssues\":[]}");
        assertThat(evaluator.requirementApproved(result,12L)).isTrue();
        assertThat(evaluator.requirementApproved(result,13L)).isFalse();
    }
}
