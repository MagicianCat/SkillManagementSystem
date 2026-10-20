-- Give every requirement inside a project group its own Feishu chat.
-- Existing requirements receive the same asynchronous chat creation event as new ones.
INSERT IGNORE INTO collab_feishu_binding(project_id,status,time_created,time_updated)
SELECT p.id,'PENDING',NOW(3),NOW(3)
FROM collab_project p
WHERE p.group_id IS NOT NULL;

INSERT IGNORE INTO collab_outbox(project_id,event_type,aggregate_type,aggregate_id,unique_dedupe_key,
                                payload_json,status,attempts,next_attempt_at,time_created,time_updated)
SELECT p.id,'CREATE_CHAT','PROJECT',p.id,CONCAT('chat:',p.project_key),
       JSON_OBJECT('projectKey',p.project_key),'PENDING',0,NOW(3),NOW(3),NOW(3)
FROM collab_project p
JOIN collab_feishu_binding b ON b.project_id=p.id
WHERE p.group_id IS NOT NULL AND (b.chat_id IS NULL OR b.chat_id='');
