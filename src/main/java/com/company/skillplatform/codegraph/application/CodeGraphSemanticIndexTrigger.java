package com.company.skillplatform.codegraph.application;

/** Starts the optional semantic sidecar after the structural graph is ready. */
@FunctionalInterface
public interface CodeGraphSemanticIndexTrigger {
    void requestForJob(long generationJobId);
}
