package com.company.skillplatform.knowledge.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("knowledge")
public class KnowledgeProperties {
    private boolean enabled = true;
    private final Qdrant qdrant = new Qdrant();
    private final Embedding embedding = new Embedding();
    private final Rerank rerank = new Rerank();
    private final Search search = new Search();
    private final Chunk chunk = new Chunk();
    private final Reconcile reconcile = new Reconcile();
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public Qdrant getQdrant() { return qdrant; } public Embedding getEmbedding() { return embedding; }
    public Rerank getRerank() { return rerank; } public Search getSearch() { return search; }
    public Chunk getChunk() { return chunk; } public Reconcile getReconcile() { return reconcile; }

    public static class Qdrant {
        private String baseUrl = "http://127.0.0.1:6333"; private String apiKey = ""; private String collection = "wiki_chunks_v1";
        private Duration connectTimeout = Duration.ofSeconds(3); private Duration requestTimeout = Duration.ofSeconds(5);
        public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;} public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;}
        public String getCollection(){return collection;} public void setCollection(String v){collection=v;} public Duration getConnectTimeout(){return connectTimeout;} public void setConnectTimeout(Duration v){connectTimeout=v;}
        public Duration getRequestTimeout(){return requestTimeout;} public void setRequestTimeout(Duration v){requestTimeout=v;}
    }
    public static class Embedding {
        private String url = "https://tkcopilot.taikang.com/v1/tongyi/embeddings"; private String apiKey = ""; private String model = "qwen3-embedding-0.6b";
        private int dimensions = 1024; private int batchSize = 16; private Duration timeout = Duration.ofSeconds(15);
        public String getUrl(){return url;} public void setUrl(String v){url=v;} public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;} public String getModel(){return model;} public void setModel(String v){model=v;}
        public int getDimensions(){return dimensions;} public void setDimensions(int v){dimensions=v;} public int getBatchSize(){return batchSize;} public void setBatchSize(int v){batchSize=v;} public Duration getTimeout(){return timeout;} public void setTimeout(Duration v){timeout=v;}
    }
    public static class Rerank {
        private boolean enabled = true; private String url = "https://tkcopilot.taikang.com/v1/tongyi/rerank"; private String apiKey = ""; private String model = "qwen3-reranker-0.6b"; private Duration timeout = Duration.ofSeconds(10);
        public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public String getUrl(){return url;} public void setUrl(String v){url=v;} public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;} public String getModel(){return model;} public void setModel(String v){model=v;} public Duration getTimeout(){return timeout;} public void setTimeout(Duration v){timeout=v;}
    }
    public static class Search {
        private int chunkTopK=12; private int documentTopK=5; private int snippetsPerDocument=2;
        public int getChunkTopK(){return chunkTopK;} public void setChunkTopK(int v){chunkTopK=v;} public int getDocumentTopK(){return documentTopK;} public void setDocumentTopK(int v){documentTopK=v;} public int getSnippetsPerDocument(){return snippetsPerDocument;} public void setSnippetsPerDocument(int v){snippetsPerDocument=v;}
    }
    public static class Chunk {
        private int targetChars=3000; private int overlapChars=400;
        public int getTargetChars(){return targetChars;} public void setTargetChars(int v){targetChars=v;} public int getOverlapChars(){return overlapChars;} public void setOverlapChars(int v){overlapChars=v;}
    }
    public static class Reconcile {
        private boolean enabled=true; private long initialDelayMs=30000; private long fixedDelayMs=600000;
        public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public long getInitialDelayMs(){return initialDelayMs;} public void setInitialDelayMs(long v){initialDelayMs=v;} public long getFixedDelayMs(){return fixedDelayMs;} public void setFixedDelayMs(long v){fixedDelayMs=v;}
    }
}
