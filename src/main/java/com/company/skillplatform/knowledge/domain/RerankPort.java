package com.company.skillplatform.knowledge.domain;

import java.util.List;

public interface RerankPort {
    List<RerankScore> rerank(String query, List<String> documents, int topN);
    record RerankScore(int index, double score) {}
}
