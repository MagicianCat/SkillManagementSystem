package com.company.skillplatform.codegraph.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGraphQueryContractTest {
    @Test
    void publicAliasesMapToWorkerOperations() {
        var graph = new CodeGraphEnginePort.GraphRef("file:///managed/graph.tar.zst", "a".repeat(64),
                List.of(new CodeGraphEnginePort.GraphRepository("backend", "backend")));
        assertThat(new CodeGraphEnginePort.QueryRequest("search", graph, "backend", Map.of()).operation()).isEqualTo("query");
        assertThat(new CodeGraphEnginePort.QueryRequest("node", graph, "backend", Map.of()).operation()).isEqualTo("context");
    }

    @Test
    void rejectsArbitraryEngineOperations() {
        var graph = new CodeGraphEnginePort.GraphRef("file:///managed/graph.tar.zst", "a".repeat(64), List.of());
        assertThatThrownBy(() -> new CodeGraphEnginePort.QueryRequest("cypher", graph, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
