package com.company.skillplatform.telemetry.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.storage.domain.ObjectStoragePort;
import com.company.skillplatform.telemetry.infrastructure.TelemetryCipher;
import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageConversationEntity;
import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageEventEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageConversationRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class SkillUsageConversationService {
    private static final int MAX_COMPRESSED_BYTES = 20 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 50 * 1024 * 1024;
    private final SkillUsageEventRepository events; private final SkillUsageConversationRepository conversations;
    private final ObjectStoragePort storage; private final TelemetryCipher cipher; private final ObjectMapper json; private final Clock clock = Clock.systemUTC();

    public SkillUsageConversationService(SkillUsageEventRepository events, SkillUsageConversationRepository conversations,
            ObjectStoragePort storage, TelemetryCipher cipher, ObjectMapper json) { this.events = events; this.conversations = conversations; this.storage = storage; this.cipher = cipher; this.json = json; }

    @Transactional
    public void stage(String eventUuid, Long userId, byte[] compressed) {
        if (compressed == null || compressed.length == 0 || compressed.length > MAX_COMPRESSED_BYTES) throw error("CONVERSATION_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
        SkillUsageEventEntity event = event(eventUuid, userId);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            JsonNode body = json.readTree(readLimited(gzip, MAX_EXPANDED_BYTES));
            if (!body.isObject() || !body.path("messages").isArray()) throw error("CONVERSATION_INVALID", HttpStatus.BAD_REQUEST);
        }
        catch (IOException ex) { throw error("CONVERSATION_INVALID", HttpStatus.BAD_REQUEST); }
        String key = "telemetry/codebuddy/" + userId + "/" + hash(event.getClientSessionId()) + "/chunks/" + eventUuid + ".json.gz.enc";
        if (!storage.exists(key)) { byte[] encrypted = cipher.encryptBytes(compressed); storage.put(key, new ByteArrayInputStream(encrypted), encrypted.length, "application/octet-stream"); }
        event.stageConversation(key); events.save(event);
    }

    @Transactional
    public void merge(String eventUuid) {
        SkillUsageEventEntity event = events.findByEventUuid(eventUuid).orElseThrow(() -> error("EVENT_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!"STAGED".equals(event.getConversationStatus())) return;
        SkillUsageConversationEntity session = conversations.findByUserIdAndClientSessionId(event.getUserId(), event.getClientSessionId()).orElseGet(() -> conversations.save(new SkillUsageConversationEntity(event.getUser(), event.getClientSessionId(), clock.instant())));
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        if (session.getCurrentObjectKey() != null && storage.exists(session.getCurrentObjectKey())) readMessages(session.getCurrentObjectKey(), merged);
        readMessages(event.getConversationChunkObjectKey(), merged);
        ArrayNode messages = json.createArrayNode(); merged.values().forEach(messages::add);
        ObjectNode output = json.createObjectNode(); output.put("schemaVersion", 1); output.put("clientSessionId", event.getClientSessionId()); output.set("messages", messages);
        byte[] zipped = gzip(output.toString().getBytes(StandardCharsets.UTF_8)); byte[] encrypted = cipher.encryptBytes(zipped);
        String key = "telemetry/codebuddy/" + event.getUserId() + "/" + hash(event.getClientSessionId()) + "/versions/v" + (session.getConversationVersion() + 1) + "-" + UUID.randomUUID() + ".json.gz.enc";
        storage.put(key, new ByteArrayInputStream(encrypted), encrypted.length, "application/octet-stream");
        session.merged(key, messages.size(), clock.instant()); conversations.save(session); event.markConversationStatus("MERGED", null); events.save(event); storage.delete(event.getConversationChunkObjectKey());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int incrementAttempt(String eventUuid) {
        SkillUsageEventEntity event = events.findByEventUuid(eventUuid).orElse(null);
        if (event == null || !"STAGED".equals(event.getConversationStatus())) return 0;
        event.incrementConversationAttempts();
        events.saveAndFlush(event);
        return event.getConversationAttempts();
    }

    @Transactional
    public void fail(String eventUuid, String code) {
        events.findByEventUuid(eventUuid).ifPresent(event -> {
            event.markConversationStatus("FAILED", code);
            events.save(event);
        });
    }

    @Transactional(readOnly = true)
    public ConversationPage readLatest(Long userId, String clientSessionId, int page, int size) {
        SkillUsageConversationEntity session = conversations.findForRead(userId, clientSessionId).orElse(null);
        if (session == null || session.getCurrentObjectKey() == null || !"ACTIVE".equals(session.getStatus())) {
            return new ConversationPage("NOT_AVAILABLE", 0, 0, List.of());
        }
        try (InputStream in = storage.get(session.getCurrentObjectKey());
             GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(cipher.decryptBytes(in.readAllBytes())))) {
            JsonNode body = json.readTree(readLimited(gzip, MAX_EXPANDED_BYTES));
            List<JsonNode> messages = new ArrayList<>();
            body.path("messages").forEach(messages::add);
            int boundedSize = Math.min(Math.max(size, 1), 100);
            int boundedPage = Math.max(page, 0);
            int from = Math.min(boundedPage * boundedSize, messages.size());
            int to = Math.min(from + boundedSize, messages.size());
            List<JsonNode> result = messages.subList(from, to).stream().toList();
            return new ConversationPage("AVAILABLE", session.getConversationVersion(), messages.size(), result);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read conversation", ex);
        }
    }

    private void readMessages(String key, Map<String, JsonNode> target) {
        try (InputStream in = storage.get(key); GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(cipher.decryptBytes(in.readAllBytes())))) {
            JsonNode body = json.readTree(readLimited(gzip, MAX_EXPANDED_BYTES)); for (JsonNode message : body.path("messages")) { String id = message.path("id").asText(); if (!id.isBlank()) target.put(id, message); }
        } catch (IOException ex) { throw new IllegalStateException("Unable to merge conversation", ex); }
    }
    private SkillUsageEventEntity event(String uuid, Long userId) { SkillUsageEventEntity event = events.findByEventUuid(uuid).orElseThrow(() -> error("EVENT_NOT_FOUND", HttpStatus.NOT_FOUND)); if (!Objects.equals(event.getUserId(), userId)) throw error("EVENT_NOT_FOUND", HttpStatus.NOT_FOUND); return event; }
    private byte[] gzip(byte[] bytes) { try (ByteArrayOutputStream out = new ByteArrayOutputStream(); GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(bytes); gzip.finish(); return out.toByteArray(); } catch (IOException ex) { throw new IllegalStateException(ex); } }
    private byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IOException("Conversation expanded payload is too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private BusinessException error(String code, HttpStatus status) { return new BusinessException(code, code, status); }

    public record ConversationPage(String status, long version, int totalMessages, List<JsonNode> messages) {}
}
