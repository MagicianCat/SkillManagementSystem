package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphReusePlannerM4Test {
    private static final RepositoryInput REPOSITORY = new RepositoryInput("repo-key", "backend", "a".repeat(40), "b".repeat(40), "file:///source.tar", "c".repeat(64));

    @Test
    void choosesNearestCompatibleAncestorAndRecordsBaseSnapshot() {
        var planner = new CodeGraphReusePlanner(ignored -> Map.of(), ignored -> List.of(
                new CodeGraphReusePlanner.SnapshotCandidate(10, "old-10", "READY", "1".repeat(40), "2".repeat(40)),
                new CodeGraphReusePlanner.SnapshotCandidate(11, "old-11", "READY", "3".repeat(40), "4".repeat(40))),
                (repository, candidate) -> OptionalInt.of(candidate.snapshotId() == 10 ? 8 : 3));

        var item = planner.plan(List.of(REPOSITORY), "GITNEXUS", "1", "adapter", "cfg").repositories().get(0);

        assertThat(item.decision()).isEqualTo(CodeGraphReusePlanner.Decision.INCREMENTAL);
        assertThat(item.baseSnapshotId()).isEqualTo(11L);
        assertThat(item.commitDistance()).isEqualTo(3);
    }

    @Test
    void incompatibleOrDivergedCandidatesFallBackToFull() {
        var planner = new CodeGraphReusePlanner(ignored -> Map.of(), ignored -> List.of(
                new CodeGraphReusePlanner.SnapshotCandidate(10, "old", "READY", "1".repeat(40), "2".repeat(40))),
                (repository, candidate) -> OptionalInt.empty());

        var item = planner.plan(List.of(REPOSITORY), "GITNEXUS", "1", "adapter", "cfg").repositories().get(0);

        assertThat(item.decision()).isEqualTo(CodeGraphReusePlanner.Decision.FULL_REQUIRED);
        assertThat(item.baseSnapshotId()).isNull();
    }

    @Test
    void workerBuildModeKeepsReuseWhenExactAndFullAreMixed() {
        var exact = new CodeGraphReusePlanner.RepositoryPlan(REPOSITORY, "exact", CodeGraphReusePlanner.Decision.REUSE_EXACT, 1L);
        var full = new CodeGraphReusePlanner.RepositoryPlan(REPOSITORY, "full", CodeGraphReusePlanner.Decision.FULL_REQUIRED, null);
        var mixed = new CodeGraphReusePlanner.Plan(List.of(exact, full), false);

        assertThat(CodeGraphGenerationCoordinator.buildMode(mixed)).isEqualTo("MIXED");
        assertThat(CodeGraphGenerationCoordinator.buildMode(new CodeGraphReusePlanner.Plan(List.of(full), false))).isEqualTo("FULL");
    }
}
