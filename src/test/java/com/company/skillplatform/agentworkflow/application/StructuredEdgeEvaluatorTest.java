package com.company.skillplatform.agentworkflow.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StructuredEdgeEvaluatorTest {
    @Test void matchesSupportedStructuredConditions() {
        assertTrue(StructuredEdgeEvaluator.matches("ALWAYS", null, null, null, null, null));
        assertTrue(StructuredEdgeEvaluator.matches("RESULT_CODE_EQUALS", "resultCode", "EQ",
                "APPROVED", "APPROVED", "SUCCEEDED"));
        assertTrue(StructuredEdgeEvaluator.matches("RESULT_CODE_IN", "resultCode", "IN",
                "PASS, APPROVED", "APPROVED", "SUCCEEDED"));
        assertTrue(StructuredEdgeEvaluator.matches("AGENT_STATUS_EQUALS", "status", "EQ",
                "SUCCEEDED", "APPROVED", "SUCCEEDED"));
        assertFalse(StructuredEdgeEvaluator.matches("RESULT_CODE_EQUALS", "resultCode", "EQ",
                "APPROVED", "REVISION_REQUIRED", "SUCCEEDED"));
    }
}
