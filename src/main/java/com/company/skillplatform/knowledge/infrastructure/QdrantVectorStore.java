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
import com.company.skillplatform.knowledge.domain.ExternalRoleMetadata;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class QdrantVectorStore implements VectorStorePort {
    private final RestClient client; private final ObjectMapper mapper; private final KnowledgeProperties properties; private final MeterRegistry metrics;
    private final AtomicBoolean initialized=new AtomicBoolean();
    public QdrantVectorStore(RestClient.Builder builder,ObjectMapper mapper,KnowledgeProperties properties,MeterRegistry metrics){var config=properties.getQdrant();var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).build());factory.setReadTimeout(config.getRequestTimeout());this.client=builder.clone().requestFactory(factory).baseUrl(config.getBaseUrl()).build();this.mapper=mapper;this.properties=properties;this.metrics=metrics;}

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    private <T> T withDocumentLock(long id, java.util.function.Supplier<T> operation) {
        if (dataSource == null) return operation.get();
        String key = "wiki-vector:" + id;
        try (var connection = dataSource.getConnection();
             var acquire = connection.prepareStatement("SELECT GET_LOCK(?, 5)");
             var release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            release.setString(1, key);
            acquire.setString(1, key);
            try (var result = acquire.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("Wiki vector write lock unavailable");
            }
            try { return operation.get(); }
            finally {
                try { release.execute(); }
                catch (java.sql.SQLException failure) {
                    connection.abort(Runnable::run);
                    throw failure;
                }
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Wiki vector write lock failed", failure);
        }
    }

    @Override
    public void replaceDocument(long id, List<KnowledgeVectorPoint> points,
                                java.util.function.Supplier<ExternalRoleMetadata> metadata) {
        withDocumentLock(id, () -> {
            var current = metadata.get();
            if (current == null) { deleteUnlocked(id); return null; }
            var payload = current.toPayload();
            points.forEach(point -> point.payload().putAll(payload));
            replaceUnlocked(id, points);
            return null;
        });
    }

    @Override
    public boolean syncExternalRoleMetadata(long id, java.util.function.Supplier<ExternalRoleMetadata> metadata) {
        return withDocumentLock(id, () -> {
            var expected = metadata.get();
            if (!hasPoints(documentFilter(id))) return false;
            if (hasStaleRoleMetadata(id, expected)) {
                post("/collections/"+collection()+"/points/payload?wait=true",
                        Map.of("filter", documentFilter(id), "payload", expected.toPayload()));
                if (hasStaleRoleMetadata(id, expected)) throw new IllegalStateException("Role payload verification failed");
            }
            return true;
        });
    }

    @Override
    public boolean hasStaleRoleMetadata(long id, ExternalRoleMetadata metadata) {
        var payload = metadata.toPayload();
        return hasPoints(Map.of("must", documentFilter(id).get("must"),
                "must_not", List.of(Map.of("must", List.of(
                        Map.of("key", "roleMetadataFingerprint", "match", Map.of("value", payload.get("roleMetadataFingerprint"))),
                        Map.of("key", "roleMetadataSchemaVersion", "match", Map.of("value", 1)))))));
    }

    private boolean hasPoints(Map<String, Object> filter) {
        return !scroll(Map.of("filter", filter, "limit", 1, "with_payload", false, "with_vector", false))
                .path("result").path("points").isEmpty();
    }

    @Override public synchronized void ensureCollection(){
        if(initialized.get())return;String collection=properties.getQdrant().getCollection();
        try{JsonNode existing=get("/collections/"+collection);int size=existing.path("result").path("config").path("params").path("vectors").path("size").asInt();String distance=existing.path("result").path("config").path("params").path("vectors").path("distance").asText();if(size!=properties.getEmbedding().getDimensions()||!"Cosine".equalsIgnoreCase(distance))throw new IllegalStateException("Qdrant collection configuration mismatch");}
        catch(org.springframework.web.client.HttpClientErrorException.NotFound missing){put("/collections/"+collection,Map.of("vectors",Map.of("size",properties.getEmbedding().getDimensions(),"distance","Cosine")));}
        createIndex("documentId","integer");createIndex("teamId","integer");createIndex("platformVisible","bool");createIndex("audience","keyword");createIndex("status","keyword");createIndex("skillKeys","keyword");createIndex("developmentStages","keyword");createIndex("knowledgeCategory","keyword");createIndex("externalRoleIds","integer");createIndex("externalRoleNames","keyword");initialized.set(true);
    }

    @Override public void replaceDocument(long documentId,List<KnowledgeVectorPoint> points){withDocumentLock(documentId,()->{replaceUnlocked(documentId,points);return null;});}
    private void replaceUnlocked(long documentId,List<KnowledgeVectorPoint> points){ensureCollection();deleteUnlocked(documentId);if(points.isEmpty())return;List<Map<String,Object>> values=points.stream().map(point->Map.<String,Object>of("id",point.id(),"vector",point.vector(),"payload",point.payload())).toList();put("/collections/"+collection()+"/points?wait=true",Map.of("points",values));}
    @Override public void deleteDocument(long documentId){withDocumentLock(documentId,()->{deleteUnlocked(documentId);return null;});}
    private void deleteUnlocked(long documentId){ensureCollection();post("/collections/"+collection()+"/points/delete?wait=true",Map.of("filter",documentFilter(documentId)));}
    @Override public List<VectorSearchHit> search(float[] queryVector,KnowledgeFilter filter,int limit){
        ensureCollection();List<Map<String,Object>> visibility=new ArrayList<>();visibility.add(Map.of("key","platformVisible","match",Map.of("value",true)));if(!filter.platformOnly()&&!filter.visibleTeamIds().isEmpty())visibility.add(Map.of("key","teamId","match",Map.of("any",filter.visibleTeamIds())));
        List<Map<String,Object>> must=new ArrayList<>();
        must.add(Map.of("key","status","match",Map.of("value","ACTIVE")));
        must.add(Map.of("should",visibility));
        if(filter.knowledgeCategory()!=null)must.add(Map.of("key","knowledgeCategory","match",Map.of("value",filter.knowledgeCategory())));
        if (!filter.roleIds().isEmpty()) must.add(Map.of("key","externalRoleIds","match",Map.of("any",filter.roleIds())));
        if (filter.generalOnly()) must.add(Map.of("is_empty",Map.of("key","externalRoleIds")));
        Map<String,Object> acl=Map.of("must",must);
        JsonNode response=post("/collections/"+collection()+"/points/query",Map.of("query",queryVector,"filter",acl,"limit",limit,"with_payload",true,"with_vector",false));
        List<VectorSearchHit> hits=new ArrayList<>();for(JsonNode point:response.path("result").path("points")){Map<String,Object> payload=mapper.convertValue(point.path("payload"),new TypeReference<>(){});hits.add(new VectorSearchHit(point.path("id").asText(),point.path("score").asDouble(),payload));}return hits;
    }
    @Override public IndexedDocumentState documentState(long documentId){JsonNode points=scroll(Map.of("filter",documentFilter(documentId),"limit",1,"with_payload",true,"with_vector",false)).path("result").path("points");if(points.isEmpty())return null;Map<String,Object> p=mapper.convertValue(points.get(0).path("payload"),new TypeReference<>(){});return new IndexedDocumentState(number(p.get("revisionNo")).intValue(),String.valueOf(p.get("contentSha256")),nullableLong(p.get("teamId")),Boolean.TRUE.equals(p.get("platformVisible")),strings(p.get("skillKeys")),strings(p.get("developmentStages")),p.get("knowledgeCategory") instanceof String category?category:null,
            p.get("roleMetadataSchemaVersion") instanceof Number schema?schema.intValue():null,
            p.get("roleMetadataFingerprint") instanceof String fingerprint?fingerprint:null);}
    @Override public IndexedDocumentState documentState(long documentId,String audience){Map<String,Object> filter=Map.of("must",List.of(Map.of("key","documentId","match",Map.of("value",documentId)),Map.of("key","audience","match",Map.of("value",audience))));JsonNode points=scroll(Map.of("filter",filter,"limit",1,"with_payload",true,"with_vector",false)).path("result").path("points");if(points.isEmpty())return null;Map<String,Object> p=mapper.convertValue(points.get(0).path("payload"),new TypeReference<>(){});return new IndexedDocumentState(number(p.get("revisionNo")).intValue(),String.valueOf(p.get("contentSha256")),nullableLong(p.get("teamId")),Boolean.TRUE.equals(p.get("platformVisible")),strings(p.get("skillKeys")),strings(p.get("developmentStages")),p.get("knowledgeCategory") instanceof String category?category:null,
            p.get("roleMetadataSchemaVersion") instanceof Number schema?schema.intValue():null,
            p.get("roleMetadataFingerprint") instanceof String fingerprint?fingerprint:null);}
    @Override public void setKnowledgeCategory(long documentId,String audience,String category){
        withDocumentLock(documentId, () -> {
        ensureCollection();
        Map<String,Object> filter=Map.of("must",List.of(Map.of("key","documentId","match",Map.of("value",documentId)),Map.of("key","audience","match",Map.of("value",audience))));
        post("/collections/"+collection()+"/points/payload?wait=true",Map.of("payload",Map.of("knowledgeCategory",category),"filter",filter));
        return null;
        });
    }
    @Override public Set<Long> indexedDocumentIds(){Set<Long> ids=new LinkedHashSet<>();Object offset=null;do{Map<String,Object> body=new LinkedHashMap<>();body.put("limit",1000);body.put("with_payload",List.of("documentId"));body.put("with_vector",false);if(offset!=null)body.put("offset",offset);JsonNode result=scroll(body).path("result");for(JsonNode point:result.path("points"))if(point.path("payload").has("documentId"))ids.add(point.path("payload").path("documentId").asLong());JsonNode next=result.path("next_page_offset");offset=next.isMissingNode()||next.isNull()?null:mapper.convertValue(next,Object.class);}while(offset!=null);return ids;}

    private JsonNode scroll(Map<String,Object> body){ensureCollection();return post("/collections/"+collection()+"/points/scroll",body);}
    private Map<String,Object> documentFilter(long id){return Map.of("must",List.of(Map.of("key","documentId","match",Map.of("value",id))));}
    private void createIndex(String field,String schema){put("/collections/"+collection()+"/index?wait=true",Map.of("field_name",field,"field_schema",schema));}
    private String collection(){return properties.getQdrant().getCollection();}
    private JsonNode get(String uri){return request(HttpMethod.GET,uri,null);}
    private JsonNode post(String uri,Object body){return request(HttpMethod.POST,uri,body);}
    private JsonNode put(String uri,Object body){return request(HttpMethod.PUT,uri,body);}
    private JsonNode request(HttpMethod method,String uri,Object body){long started=System.nanoTime();try{var spec=client.method(method).uri(uri);String key=properties.getQdrant().getApiKey();if(key!=null&&!key.isBlank())spec.header("api-key",key);JsonNode response=(body==null?spec:spec.body(body)).retrieve().body(JsonNode.class);if(response==null)throw new IllegalStateException("Empty Qdrant response");return response;}finally{metrics.timer("knowledge.qdrant.duration").record(java.time.Duration.ofNanos(System.nanoTime()-started));}}
    private Number number(Object value){if(value instanceof Number number)return number;return Long.parseLong(String.valueOf(value));}
    private Long nullableLong(Object value){return value==null?null:number(value).longValue();}
    private List<String> strings(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
}
