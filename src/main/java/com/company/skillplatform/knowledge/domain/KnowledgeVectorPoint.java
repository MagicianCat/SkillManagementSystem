package com.company.skillplatform.knowledge.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KnowledgeVectorPoint(String id, float[] vector, Map<String, Object> payload) {
    public static String stableId(long documentId, int revisionNo, int chunkIndex) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((documentId + ":" + revisionNo + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            long most = bytes.getLong();
            long least = bytes.getLong();
            most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
            least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(most, least).toString();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to derive knowledge point id", failure);
        }
    }

    public static KnowledgeVectorPoint from(KnowledgeChunk chunk, float[] vector) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("documentId", chunk.documentId());
        payload.put("revisionNo", chunk.revisionNo());
        payload.put("contentSha256", chunk.contentSha256());
        payload.put("title", chunk.title());
        payload.put("heading", chunk.heading());
        payload.put("headingPath", chunk.headingPath());
        payload.put("documentType", chunk.documentType());
        payload.put("teamId", chunk.teamId());
        payload.put("platformVisible", chunk.platformVisible());
        payload.put("status", chunk.status());
        payload.put("skillKeys", chunk.skillKeys());
        payload.put("developmentStages", chunk.developmentStages());
        payload.put("chunkIndex", chunk.chunkIndex());
        payload.put("content", chunk.content());
        return new KnowledgeVectorPoint(stableId(chunk.documentId(), chunk.revisionNo(), chunk.chunkIndex()), vector, payload);
    }
}
