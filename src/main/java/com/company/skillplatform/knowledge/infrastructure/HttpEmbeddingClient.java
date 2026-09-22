package com.company.skillplatform.knowledge.infrastructure;

import com.company.skillplatform.knowledge.domain.EmbeddingPort;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class HttpEmbeddingClient implements EmbeddingPort {
    private final RestClient client; private final KnowledgeProperties properties; private final MeterRegistry metrics;
    public HttpEmbeddingClient(RestClient.Builder builder, KnowledgeProperties properties,MeterRegistry metrics) { var config=properties.getEmbedding();var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(config.getTimeout()).build());factory.setReadTimeout(config.getTimeout());this.client=builder.clone().requestFactory(factory).build(); this.properties=properties;this.metrics=metrics; }
    @Override public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        List<float[]> result=new ArrayList<>(); int batch=Math.max(1,properties.getEmbedding().getBatchSize());
        for(int from=0;from<texts.size();from+=batch) result.addAll(request(texts.subList(from,Math.min(texts.size(),from+batch))));
        return result;
    }
    @Override public float[] embedQuery(String query) { return request(List.of(query)).get(0); }
    private List<float[]> request(List<String> input) {long started=System.nanoTime();try{
        var config=properties.getEmbedding();
        JsonNode response=client.post().uri(config.getUrl()).header(HttpHeaders.AUTHORIZATION,"Bearer "+config.getApiKey())
                .body(Map.of("model",config.getModel(),"input",input)).retrieve().body(JsonNode.class);
        if(response==null||!response.path("data").isArray()||response.path("data").size()!=input.size()) throw new IllegalStateException("Embedding response count mismatch");
        List<JsonNode> rows=new ArrayList<>();response.path("data").forEach(rows::add);rows.sort(Comparator.comparingInt(n->n.path("index").asInt()));
        List<float[]> vectors=new ArrayList<>();
        for(JsonNode row:rows){JsonNode values=row.path("embedding");if(!values.isArray()||values.size()!=config.getDimensions())throw new IllegalStateException("Embedding dimension mismatch");float[] vector=new float[values.size()];for(int i=0;i<values.size();i++)vector[i]=(float)values.get(i).asDouble();vectors.add(vector);}
        return vectors;}finally{metrics.timer("knowledge.embedding.duration").record(java.time.Duration.ofNanos(System.nanoTime()-started));}
    }
}
