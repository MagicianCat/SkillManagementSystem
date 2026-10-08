package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public interface CodeGraphSemanticCatalog {
    Optional<IndexTarget> findByGenerationJob(long generationJobId);
    void updateStatus(long bindingId, String status, String errorCode);

    /** Marks the binding INDEXING only if it is not already INDEXING; returns true when this caller owns the work. */
    boolean tryMarkIndexing(long bindingId);

    /** Returns current semantic index status, empty if binding unknown. */
    Optional<String> currentStatus(long bindingId);

    /** Marks the binding DEGRADED only if it is currently READY (never overrides INDEXING). */
    void markDegradedIfReady(long bindingId, String errorCode);

    /** Returns IDs of bindings stuck in INDEXING for longer than {@code staleBeforeMinutes}. */
    List<Long> findStaleIndexing(int staleBeforeMinutes);

    /** Returns the most recent READY generation job id that targets the given binding. */
    OptionalLong findLatestReadyJobForBinding(long bindingId);

    /**
     * Returns IDs of ACTIVE bindings whose structural bundle is READY but whose
     * semantic index was never started (status DISABLED). Used by the M6 backfill
     * so pre-M6 bindings get indexed on startup.
     */
    List<Long> findDisabledReadyBindings();

    record IndexTarget(long bindingId, long bundleId, CodeGraphEnginePort.GraphRef graph,
                       List<RepositorySnapshot> repositories) {}
    record RepositorySnapshot(long snapshotId, String alias, String logicalRepositoryKey, String commitSha) {}
}
