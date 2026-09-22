package com.company.skillplatform.knowledge.application;

public record WikiKnowledgeChangedEvent(Long documentId, Operation operation) {
    public enum Operation { UPSERT, DELETE }
}
