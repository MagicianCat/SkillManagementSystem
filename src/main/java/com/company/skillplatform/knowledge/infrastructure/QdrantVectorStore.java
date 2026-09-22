package com.company.skillplatform.knowledge.infrastructure;

import com.company.skillplatform.knowledge.domain.KnowledgeVectorPoint;
import com.company.skillplatform.knowledge.domain.VectorStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class QdrantVectorStore implements VectorStorePort {
    private final RestClient client; private final ObjectMapper mapper; private final KnowledgeProperties properties; private final MeterRegistry metrics;
    private final AtomicBoolean initialized=new AtomicBoolean();
    public QdrantVectorStore(RestClient.Builder builder,ObjectMapper mapper,KnowledgeProperties properties,MeterRegistry metrics){var config=properties.getQdrant();var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).build());factory.setReadTimeout(config.getRequestTimeout());this.client=builder.clone().requestFactory(factory).baseUrl(config.getBaseUrl()).build();this.mapper=mapper;this.properties=properties;this.metrics=metrics;}

    @Override public synchronized void ensureCollection(){
        if(initialized.get())return;String collection=properties.getQdrant().getCollection();
        try{JsonNode existing=get("/collections/"+collection);int size=existing.path("result").path("config").path("params").path("vectors").path("size").asInt();String distance=existing.path("result").path("config").path("params").path("vectors").path("distance").asText();if(size!=properties.getEmbedding().getDimensions()||!"Cosine".equalsIgnoreCase(distance))throw new IllegalStateException("Qdrant collection configuration mismatch");}
        catch(org.springframework.web.client.HttpClientErrorException.NotFound missing){put("/collections/"+collection,Map.of("vectors",Map.of("size",properties.getEmbedding().getDimensions(),"distance","Cosine")));}
        createIndex("documentId","integer");createIndex("teamId","integer");createIndex("platformVisible","bool");createIndex("status","keyword");createIndex("documentType","keyword");createIndex("skillKeys","keyword");createIndex("developmentStages","keyword");initialized.set(true);
    }

    @Override public void replaceDocument(long documentId,List<KnowledgeVectorPoint> points){ensureCollection();deleteDocument(documentId);if(points.isEmpty())return;List<Map<String,Object>> values=points.stream().map(point->Map.<String,Object>of("id",point.id(),"vector",point.vector(),"payload",point.payload())).toList();put("/collections/"+collection()+"/points?wait=true",Map.of("points",values));}
    @Override public void deleteDocument(long documentId){ensureCollection();post("/collections/"+collection()+"/points/delete?wait=true",Map.of("filter",documentFilter(documentId)));}
    @Override public List<VectorSearchHit> search(float[] queryVector,KnowledgeFilter filter,int limit){
        ensureCollection();List<Map<String,Object>> visibility=new ArrayList<>();visibility.add(Map.of("key","platformVisible","match",Map.of("value",true)));if(!filter.platformOnly()&&!filter.visibleTeamIds().isEmpty())visibility.add(Map.of("key","teamId","match",Map.of("any",filter.visibleTeamIds())));
        Map<String,Object> acl=Map.of("must",List.of(Map.of("key","status","match",Map.of("value","ACTIVE")),Map.of("should",visibility)));
        JsonNode response=post("/collections/"+collection()+"/points/query",Map.of("query",queryVector,"filter",acl,"limit",limit,"with_payload",true,"with_vector",false));
        List<VectorSearchHit> hits=new ArrayList<>();for(JsonNode point:response.path("result").path("points")){Map<String,Object> payload=mapper.convertValue(point.path("payload"),new TypeReference<>(){});hits.add(new VectorSearchHit(point.path("id").asText(),point.path("score").asDouble(),payload));}return hits;
    }
    @Override public IndexedDocumentState documentState(long documentId){JsonNode points=scroll(Map.of("filter",documentFilter(documentId),"limit",1,"with_payload",true,"with_vector",false)).path("result").path("points");if(points.isEmpty())return null;Map<String,Object> p=mapper.convertValue(points.get(0).path("payload"),new TypeReference<>(){});return new IndexedDocumentState(number(p.get("revisionNo")).intValue(),String.valueOf(p.get("contentSha256")),nullableLong(p.get("teamId")),Boolean.TRUE.equals(p.get("platformVisible")),strings(p.get("skillKeys")),strings(p.get("developmentStages")));}
    @Override public Set<Long> indexedDocumentIds(){Set<Long> ids=new LinkedHashSet<>();Object offset=null;do{Map<String,Object> body=new LinkedHashMap<>();body.put("limit",1000);body.put("with_payload",List.of("documentId"));body.put("with_vector",false);if(offset!=null)body.put("offset",offset);JsonNode result=scroll(body).path("result");for(JsonNode point:result.path("points"))if(point.path("payload").has("documentId"))ids.add(point.path("payload").path("documentId").asLong());JsonNode next=result.path("next_page_offset");offset=next.isMissingNode()||next.isNull()?null:mapper.convertValue(next,Object.class);}while(offset!=null);return ids;}

    private JsonNode scroll(Map<String,Object> body){ensureCollection();return post("/collections/"+collection()+"/points/scroll",body);}
    private Map<String,Object> documentFilter(long id){return Map.of("must",List.of(Map.of("key","documentId","match",Map.of("value",id))));}
    private void createIndex(String field,String schema){put("/collections/"+collection()+"/index",Map.of("field_name",field,"field_schema",schema));}
    private String collection(){return properties.getQdrant().getCollection();}
    private JsonNode get(String uri){return request(HttpMethod.GET,uri,null);}
    private JsonNode post(String uri,Object body){return request(HttpMethod.POST,uri,body);}
    private JsonNode put(String uri,Object body){return request(HttpMethod.PUT,uri,body);}
    private JsonNode request(HttpMethod method,String uri,Object body){long started=System.nanoTime();try{var spec=client.method(method).uri(uri);String key=properties.getQdrant().getApiKey();if(key!=null&&!key.isBlank())spec.header("api-key",key);JsonNode response=(body==null?spec:spec.body(body)).retrieve().body(JsonNode.class);if(response==null)throw new IllegalStateException("Empty Qdrant response");return response;}finally{metrics.timer("knowledge.qdrant.duration").record(java.time.Duration.ofNanos(System.nanoTime()-started));}}
    private Number number(Object value){if(value instanceof Number number)return number;return Long.parseLong(String.valueOf(value));}
    private Long nullableLong(Object value){return value==null?null:number(value).longValue();}
    private List<String> strings(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
}
