package com.company.skillplatform.codegraph.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the optional Qdrant semantic sidecar. */
@ConfigurationProperties("skill-platform.code-graph.semantic")
public class CodeGraphSemanticProperties {
    private boolean enabled = true;
    private String collection = "code_graph_symbols_v1";
    private int topK = 20;
    private int preloadMaxItems = 12;
    private int preloadMaxChars = 8000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getPreloadMaxItems() { return preloadMaxItems; }
    public void setPreloadMaxItems(int preloadMaxItems) { this.preloadMaxItems = preloadMaxItems; }
    public int getPreloadMaxChars() { return preloadMaxChars; }
    public void setPreloadMaxChars(int preloadMaxChars) { this.preloadMaxChars = preloadMaxChars; }
}
