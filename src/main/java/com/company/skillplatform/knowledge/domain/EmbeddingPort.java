package com.company.skillplatform.knowledge.domain;

import java.util.List;

public interface EmbeddingPort {
    List<float[]> embedDocuments(List<String> texts);
    float[] embedQuery(String query);
}
