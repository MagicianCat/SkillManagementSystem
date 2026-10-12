package com.company.skillplatform.agentworkflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StageCompletionEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StageCompletionEvaluator evaluator = new StageCompletionEvaluator();

    @Test void approvesOnlyTheLatestReviewedRevisionWithoutBlockers() throws Exception {
        var result=json.readTree("{\"resultCode\":\"APPROVED\",\"summary\":\"ok\",\"issues\":[]}");
        assertThat(evaluator.reviewApproved(result,12L)).isTrue();
        assertThat(evaluator.reviewApproved(result,null)).isFalse();
    }
}
