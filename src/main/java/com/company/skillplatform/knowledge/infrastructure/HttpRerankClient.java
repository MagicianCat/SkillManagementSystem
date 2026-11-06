package com.company.skillplatform.knowledge.infrastructure;

import com.company.skillplatform.knowledge.domain.RerankPort;
import com.company.skillplatform.ops.OpsEventService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class HttpRerankClient implements RerankPort {
    private final OpsEventService ops;private final RestClient client; private final KnowledgeProperties properties; private final MeterRegistry metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public HttpRerankClient(RestClient.Builder builder,KnowledgeProperties properties,MeterRegistry metrics,OpsEventService ops){var config=properties.getRerank();var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(config.getTimeout()).build());factory.setReadTimeout(config.getTimeout());this.client=builder.clone().requestFactory(factory).build();this.properties=properties;this.metrics=metrics;this.ops=ops;}
    public HttpRerankClient(RestClient.Builder builder,KnowledgeProperties properties,MeterRegistry metrics){this(builder,properties,metrics,null);}
    @Override public List<RerankScore> rerank(String query,List<String> documents,int topN){long started=System.nanoTime();try{
        var config=properties.getRerank();String key=config.getApiKey().isBlank()?properties.getEmbedding().getApiKey():config.getApiKey();
        JsonNode response=client.post().uri(config.getUrl()).header(HttpHeaders.AUTHORIZATION,"Bearer "+key)
                .body(Map.of("model",config.getModel(),"query",query,"documents",documents,"top_n",Math.min(topN,documents.size()))).retrieve().body(JsonNode.class);
        if(response==null||!response.path("results").isArray())throw new IllegalStateException("Invalid rerank response");
        List<RerankScore> result=new ArrayList<>();response.path("results").forEach(row->{int index=row.path("index").asInt(-1);if(index>=0&&index<documents.size())result.add(new RerankScore(index,row.path("relevance_score").asDouble()));});if(ops!=null)ops.record(new OpsEventService.Event(null,"knowledge.rerank.request","RERANK","rerank","SUCCESS",config.getModel(),null,null,null,null,null,(System.nanoTime()-started)/1_000_000,null));return result;}catch(RuntimeException failure){if(ops!=null)ops.recordFailure(new OpsEventService.Event(null,"knowledge.rerank.request","RERANK","rerank","DEGRADED",properties.getRerank().getModel(),"RERANK_REQUEST_FAILED",null,null,null,null,(System.nanoTime()-started)/1_000_000,null));throw failure;}finally{metrics.timer("knowledge.rerank.duration").record(java.time.Duration.ofNanos(System.nanoTime()-started));}
    }
}
