package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphReusePlannerTest {
    @Test
    void onlyReadySnapshotsAreExactReusable() {
        var repository = new RepositoryInput("repo-key", "backend", "a".repeat(40), "b".repeat(40),
                "file:///source.tar", "c".repeat(64));
        var planner = new CodeGraphReusePlanner(fingerprints -> Map.of(fingerprints.get(0),
                new CodeGraphReusePlanner.SnapshotMatch(9L, fingerprints.get(0), "READY")));

        assertThat(planner.plan(List.of(repository), "GITNEXUS", "1", "adapter", "cfg").repositories().get(0).decision())
                .isEqualTo(CodeGraphReusePlanner.Decision.REUSE_EXACT);
    }

    @Test
    void aMissingSnapshotMakesTheBundleRequireFullBuild() {
        var repository = new RepositoryInput("repo-key", "backend", "a".repeat(40), "b".repeat(40),
                "file:///source.tar", "c".repeat(64));
        var plan = new CodeGraphReusePlanner(ignored -> Map.of())
                .plan(List.of(repository), "GITNEXUS", "1", "adapter", "cfg");
        assertThat(plan.repositories().get(0).decision()).isEqualTo(CodeGraphReusePlanner.Decision.FULL_REQUIRED);
        assertThat(plan.fullyReusable()).isFalse();
    }
}
