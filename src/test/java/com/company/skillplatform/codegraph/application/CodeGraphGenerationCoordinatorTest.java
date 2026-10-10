package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.Artifact;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CodeGraphGenerationCoordinatorTest {
    @Test
    void exactBundleReuseSkipsWorkerBuild() {
        var calls = new AtomicInteger();
        var engine = engine(calls, CodeGraphEnginePort.State.SUCCEEDED);
        var store = new FakeStore(true, true);
        var result = new CodeGraphGenerationCoordinator(engine, store, mock(CodeGraphLifecyclePublisher.class)).start(command());
        assertThat(result.reused()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(store.activated).isTrue();
    }

    @Test
    void forcedRebuildBypassesReadyBundleAndSubmitsFullBuild() {
        var calls = new AtomicInteger();
        var store = new FakeStore(true, true);
        var result = new CodeGraphGenerationCoordinator(engine(calls, CodeGraphEnginePort.State.SUCCEEDED), store,
                mock(CodeGraphLifecyclePublisher.class)).start(command(), true);

        assertThat(result.reused()).isFalse();
        assertThat(result.status()).isEqualTo("BUILDING");
        assertThat(calls).hasValue(1);
        assertThat(store.activated).isFalse();
    }

    @Test
    void readyBundleRequestsNonBlockingSemanticIndexForBuildAndReuse() {
        var trigger = mock(CodeGraphSemanticIndexTrigger.class);
        var reused = new CodeGraphGenerationCoordinator(engine(new AtomicInteger(), CodeGraphEnginePort.State.SUCCEEDED),
                new FakeStore(true, true), mock(CodeGraphLifecyclePublisher.class), null, trigger);
        reused.start(command());
        verify(trigger).requestForJob(3L);

        var built = new CodeGraphGenerationCoordinator(engine(new AtomicInteger(), CodeGraphEnginePort.State.SUCCEEDED),
                new FakeStore(false, false), mock(CodeGraphLifecyclePublisher.class), null, trigger);
        var start = built.start(command());
        built.poll(start.jobId());
        verify(trigger, org.mockito.Mockito.times(2)).requestForJob(3L);
    }

    @Test
    void successfulBuildOnlyActivatesAfterArtifactIsReady() {
        var calls = new AtomicInteger();
        var store = new FakeStore(false, false);
        var coordinator = new CodeGraphGenerationCoordinator(engine(calls, CodeGraphEnginePort.State.SUCCEEDED), store, mock(CodeGraphLifecyclePublisher.class));
        var start = coordinator.start(command());
        assertThat(store.activated).isFalse();
        assertThat(coordinator.poll(start.jobId()).status()).isEqualTo("READY");
        assertThat(store.activated).isTrue();
    }

    private static GenerationCommand command() {
        return new GenerationCommand(7, "INITIAL", "GITNEXUS", "1", "adapter", "cfg", "group",
                List.of(new RepositoryInput("repo", "backend", "a".repeat(40), "b".repeat(40),
                        "file:///source.tar", "c".repeat(64))));
    }

    private static CodeGraphEnginePort engine(AtomicInteger calls, CodeGraphEnginePort.State state) {
        return new CodeGraphEnginePort() {
            public BuildHandle build(BuildRequest request) { calls.incrementAndGet(); return new BuildHandle("engine-1", "QUEUED"); }
            public BuildStatus status(String id) { return new BuildStatus(id, state, 100, "PUBLISH",
                    new Artifact("file:///graph.tar.zst", "d".repeat(64), "GITNEXUS", "1", "adapter", "FULL"), null, null); }
        };
    }

    private static final class FakeStore implements CodeGraphMetadataStore {
        private final boolean snapshots; private final boolean bundle; private boolean activated;
        private FakeStore(boolean snapshots, boolean bundle) { this.snapshots = snapshots; this.bundle = bundle; }
        public Map<String, CodeGraphReusePlanner.SnapshotMatch> findReadySnapshots(List<String> fps) { return snapshots ? Map.of(fps.get(0), new CodeGraphReusePlanner.SnapshotMatch(1, fps.get(0), "READY")) : Map.of(); }
        public OptionalLong findReadyBundle(String hash) { return bundle ? OptionalLong.of(2) : OptionalLong.empty(); }
        public long activateReused(GenerationCommand c, CodeGraphReusePlanner.Plan p, String h, long id) { activated = true; return 3; }
        public PendingJob createBuild(GenerationCommand c, CodeGraphReusePlanner.Plan p, String h, String request, String key) { return new PendingJob(3, 4, request, key); }
        public void attachEngineJob(long jobId, String engineJobId) {}
        public PollableJob pollable(long jobId) { return new PollableJob(jobId, 7, "engine-1", "BUILDING"); }
        public void updateProgress(long jobId, int progress) {}
        public boolean complete(long jobId, CodeGraphEnginePort.BuildStatus status) { activated = true; return true; }
        public void fail(long jobId, String code, String message) {}
    }
}
