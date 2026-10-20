package com.company.skillplatform.collabproject.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Records Group Feishu work in the caller's transaction, even before a requirement exists. */
@Service
public class GroupFeishuOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public GroupFeishuOutbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void enqueue(long groupId, String eventType, long aggregateId, String dedupeKey, Object payload) {
        String aggregateType = switch (eventType) {
            case "CREATE_CHAT", "CREATE_WIKI_FOLDER" -> "GROUP";
            case "ADD_MEMBER", "REMOVE_MEMBER" -> "MEMBER";
            default -> throw new IllegalArgumentException("Unsupported Group Feishu event: " + eventType);
        };
        try {
            jdbc.update("INSERT IGNORE INTO collab_group_outbox(group_id,event_type,aggregate_type,aggregate_id,unique_dedupe_key,payload_json,status,attempts,next_attempt_at,time_created,time_updated) "
                            + "VALUES(?,?,?,?,?,?,'PENDING',0,NOW(3),NOW(3),NOW(3))",
                    groupId, eventType, aggregateType, aggregateId, dedupeKey, json.writeValueAsString(payload));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid Group Feishu event payload", error);
        }
    }
}
