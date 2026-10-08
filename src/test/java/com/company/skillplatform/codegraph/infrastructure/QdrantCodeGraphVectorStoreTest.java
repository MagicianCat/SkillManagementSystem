package com.company.skillplatform.codegraph.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorPoint;
import com.company.skillplatform.codegraph.domain.CodeGraphVectorStorePort.CodeGraphVectorSearchFilter;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QdrantCodeGraphVectorStoreTest {
    @Test
    void usesDedicatedCollectionAndWritesBundlePayload() {
        KnowledgeProperties knowledge = new KnowledgeProperties();
        knowledge.getQdrant().setBaseUrl("http://qdrant.test");
        CodeGraphSemanticProperties semantic = new CodeGraphSemanticProperties();
        semantic.setCollection("code_graph_symbols_v1");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://qdrant.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1"))
                .andExpect(method(HttpMethod.GET)).andRespond(withResourceNotFound());
        server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1"))
                .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1/index"))
                    .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1/points?wait=true"))
                .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        QdrantCodeGraphVectorStore store = new QdrantCodeGraphVectorStore(
                builder.build(), new ObjectMapper(), knowledge, semantic, new SimpleMeterRegistry());
        store.upsertBundle(20L, List.of(new CodeGraphVectorPoint("stable-id", new float[]{.1f, .2f},
                Map.of("bundleId", 20L, "nodeType", "METHOD", "name", "start"))));
        server.verify();
    }

    @Test
    void buildsPayloadFiltersAndMapsSearchHits() {
        KnowledgeProperties knowledge = new KnowledgeProperties();
        knowledge.getQdrant().setBaseUrl("http://qdrant.test");
        CodeGraphSemanticProperties semantic = new CodeGraphSemanticProperties();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://qdrant.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
                        "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}}}}",
                        MediaType.APPLICATION_JSON));
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1/index"))
                    .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo("http://qdrant.test/collections/code_graph_symbols_v1/points/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.filter.must[0].key").value("bundleId"))
                .andExpect(jsonPath("$.filter.must[0].match.value").value(20))
                .andExpect(jsonPath("$.filter.must[4].key").value("nodeType"))
                .andRespond(withSuccess(
                        "{\"result\":{\"points\":[{\"id\":\"p1\",\"score\":0.91,\"payload\":{\"bundleId\":20,\"nodeType\":\"METHOD\"}}]}}",
                        MediaType.APPLICATION_JSON));

        QdrantCodeGraphVectorStore store = new QdrantCodeGraphVectorStore(
                builder.build(), new ObjectMapper(), knowledge, semantic, new SimpleMeterRegistry());
        var hits = store.search(new float[]{.1f, .2f}, new CodeGraphVectorSearchFilter(
                20L, 10L, "backend", "abc", Set.of("METHOD", "API")), 20);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).pointId()).isEqualTo("p1");
        assertThat(hits.get(0).payload()).containsEntry("nodeType", "METHOD");
        server.verify();
    }
}
