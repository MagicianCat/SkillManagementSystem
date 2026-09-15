package com.company.skillplatform.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentFeishuCallGuardTest {
    @Test
    void boundsSearchesAndPreventsDuplicateDocumentReads() {
        var guard = new AgentFeishuCallGuard();

        assertThat(guard.beforeSearch("run").allowed()).isTrue();
        assertThat(guard.beforeSearch("run").allowed()).isTrue();
        assertThat(guard.beforeSearch("run").allowed()).isTrue();
        assertThat(guard.beforeSearch("run").code()).isEqualTo("search_budget_exhausted");

        assertThat(guard.beforeRead("run", "doc-a").allowed()).isTrue();
        guard.readFailed("run", "doc-a");
        assertThat(guard.beforeRead("run", "doc-a").code()).isEqualTo("duplicate_skipped");
    }

    @Test
    void stopsAfterThreeSuccessfulDocuments() {
        var guard = new AgentFeishuCallGuard();
        for (String doc : new String[]{"a", "b", "c"}) {
            assertThat(guard.beforeRead("run", doc).allowed()).isTrue();
            guard.readSucceeded("run", doc);
        }
        assertThat(guard.beforeRead("run", "d").code()).isEqualTo("evidence_budget_reached");
    }
}
