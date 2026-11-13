package com.company.skillplatform.collabproject.progress.application;

import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.collabproject.progress.infrastructure.FeishuProgressDocGateway;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/** Single-lease projection. Uncertain remote creates are reconciled before any retry. */
@Component
public class CollabProgressProjectionWorker {
    private static final Logger log = LoggerFactory.getLogger(CollabProgressProjectionWorker.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FeishuDocumentMcpProxy documents;
    private final FeishuProgressDocGateway gateway;
    private final CollabProgressSnapshotService snapshots;
    private final String groupIds;
    private final String requirementIds;
    private final String owner = UUID.randomUUID().toString();

    public CollabProgressProjectionWorker(JdbcTemplate jdbc, ObjectMapper json, FeishuDocumentMcpProxy documents,
            FeishuProgressDocGateway gateway, CollabProgressSnapshotService snapshots,
            @Value("${skill-platform.collab-progress.group-ids:}") String groupIds,
            @Value("${skill-platform.collab-progress.requirement-ids:}") String requirementIds) {
        this.jdbc = jdbc; this.json = json; this.documents = documents; this.gateway = gateway; this.snapshots = snapshots; this.groupIds = groupIds; this.requirementIds = requirementIds;
    }

    @Scheduled(fixedDelayString = "${skill-platform.collab-progress.worker-interval-ms:15000}")
    public void drain() {
        List<Long> ids = jdbc.query("select d.requirement_id from collab_requirement_progress_doc d join collab_project p on p.id=d.requirement_id join collab_feishu_binding b on b.project_id=d.requirement_id where d.bootstrap_state='COMPLETE' and b.wiki_parent_node_token is not null and (?='' or find_in_set(cast(p.group_id as char),?)>0) and (?='' or find_in_set(cast(p.id as char),?)>0) and (d.document_token is null or d.desired_revision>d.applied_revision or d.status<>'READY' or (d.chat_permission_status<>'GRANTED' and b.chat_id is not null and (d.chat_permission_last_attempt_at is null or d.chat_permission_last_attempt_at<date_sub(now(3),interval 5 minute)))) and (d.lease_until is null or d.lease_until<now(3)) and d.status not in ('AUTH_REQUIRED','RECONCILE_REQUIRED') and (d.status<>'ERROR' or d.time_updated<=date_sub(now(3),interval least(300,pow(2,least(d.attempts,8))) second)) order by d.time_updated,d.id limit 10", (rs, n) -> rs.getLong(1), groupIds, groupIds, requirementIds, requirementIds);
        for (Long id : ids) {
            if (jdbc.update("update collab_requirement_progress_doc set lease_owner=?,lease_until=date_add(now(3),interval 3 minute) where requirement_id=? and (lease_until is null or lease_until<now(3))", owner, id) != 1) continue;
            try { project(id); }
            catch (Exception ex) { fail(id, ex); }
            finally { jdbc.update("update collab_requirement_progress_doc set lease_owner=null,lease_until=null where requirement_id=? and lease_owner=?", id, owner); }
        }
    }

    private void project(long id) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("select d.status,d.document_token,d.wiki_node_token,d.document_url,d.block_map_json,d.desired_revision,d.applied_revision,d.chat_permission_status,p.name,b.wiki_space_id,b.wiki_parent_node_token,b.publish_actor_user_id,b.chat_id from collab_requirement_progress_doc d join collab_project p on p.id=d.requirement_id join collab_feishu_binding b on b.project_id=p.id where d.requirement_id=?", id);
        long revision = ((Number) row.get("desired_revision")).longValue();
        long actor = ((Number) row.get("publish_actor_user_id")).longValue();
        String space = String.valueOf(row.get("wiki_space_id"));
        String parent = String.valueOf(row.get("wiki_parent_node_token"));
        String title = "研发进度跟踪｜" + row.get("name");
        String doc = (String) row.get("document_token");
        if (doc != null && "READY".equals(row.get("status")) && revision <= ((Number)row.get("applied_revision")).longValue()) {
            grantChatPermission(id, actor, doc, (String)row.get("chat_id"));
            return;
        }
        if (doc == null) {
            JsonNode found = findExisting(actor, space, parent, title);
            if (found != null) {
                doc = found.path("obj_token").asText("");
                String node = found.path("node_token").asText("");
                if (doc.isBlank() || node.isBlank()) throw new IllegalStateException("Matching Wiki node has no DOCX token");
                persistNode(id, space, parent, node, doc, found.path("url").asText("https://www.feishu.cn/wiki/" + node));
            } else {
                if (!"PENDING_CREATE".equals(row.get("status"))) throw new ReconcileException("Remote create result is unknown");
                jdbc.update("update collab_requirement_progress_doc set status='CREATING',time_updated=now(3) where requirement_id=?", id);
                JsonNode created = documents.createWikiDocx(actor, space, parent, title);
                doc = created.path("documentToken").asText("");
                String node = created.path("nodeToken").asText("");
                if (doc.isBlank() || node.isBlank()) throw new ReconcileException("Wiki DOCX create returned no tokens");
                persistNode(id, space, parent, node, doc, created.path("documentUrl").asText("https://www.feishu.cn/wiki/" + node));
            }
        }
        jdbc.update("update collab_requirement_progress_doc set status='WRITING',time_updated=now(3) where requirement_id=?", id);
        CollabProgressSnapshotService.Snapshot snapshot = snapshots.read(id);
        List<JsonNode> blocks = gateway.allBlocks(actor, doc);
        JsonNode savedMap = row.get("block_map_json") == null ? json.createObjectNode() : json.readTree(String.valueOf(row.get("block_map_json")));
        String overviewId = savedMap.path("overview").asText("");
        if (!overviewId.isBlank()) {
            String expectedOverview = overviewId;
            if (blocks.stream().noneMatch(b -> expectedOverview.equals(b.path("block_id").asText())))
                throw new ReconcileException("Progress overview block was removed");
        }
        if (overviewId.isBlank()) overviewId = rootText(blocks, doc);
        if (overviewId == null) {
            overviewId = gateway.addText(actor, doc, doc, "研发进度跟踪");
            blocks = gateway.allBlocks(actor, doc);
        }
        List<JsonNode> tables = rootTables(blocks, doc);
        String stageTableId = savedMap.path("tables").path(0).asText("");
        if (!stageTableId.isBlank()) {
            String expected = stageTableId;
            JsonNode stageTable = tables.stream().filter(t -> expected.equals(t.path("block_id").asText())).findFirst().orElse(null);
            if (stageTable == null) throw new ReconcileException("Progress stage table was removed or moved");
            // Only delete detail tables whose IDs were saved by this integration.
            for (int i = 1; i < savedMap.path("tables").size(); i++) {
                String detailId = savedMap.path("tables").path(i).asText("");
                if (detailId.isBlank()) continue;
                List<JsonNode> children = rootChildren(blocks, doc);
                int index = -1;
                for (int j = 0; j < children.size(); j++)
                    if (detailId.equals(children.get(j).path("block_id").asText())
                            && children.get(j).path("block_type").asInt() == 31) { index = j; break; }
                if (index >= 0) {
                    gateway.deleteChild(actor, doc, doc, index);
                    blocks = gateway.allBlocks(actor, doc);
                }
            }
            tables = List.of(stageTable);
        }
        if (stageTableId.isBlank()) tables = tables.stream().filter(t ->
                t.path("table").path("property").path("row_size").asInt() == 9
                        && t.path("table").path("property").path("column_size").asInt() == 7).limit(1).toList();
        if (tables.isEmpty()) {
            gateway.createTable(actor, doc, 9, 7);
            blocks = gateway.allBlocks(actor, doc);
            tables = rootTables(blocks, doc);
        }
        if (tables.get(0).path("table").path("property").path("row_size").asInt() != 9
                || tables.get(0).path("table").path("property").path("column_size").asInt() != 7)
            throw new ReconcileException("Progress stage table structure changed");
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put(overviewId, snapshot.overview() + "  最后同步：" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))
                + "\n更新说明：进度由研枢自动维护；审批与流程状态以研枢数据库为准。此文档不接受审批操作。缺少原始时间的历史节点不会编造流水。");
        addTableUpdates(updates, tables.get(0), snapshot.stages(), 9, 7, blocks);
        gateway.batchUpdateText(actor, doc, updates);
        String hash = sha(snapshot.toString());
        var map = json.createObjectNode(); map.put("overview", overviewId);
        map.putArray("tables").add(tables.get(0).path("block_id").asText());
        jdbc.update("update collab_requirement_progress_doc set status='READY',applied_revision=?,last_snapshot_sha256=?,block_map_json=?,last_applied_event_id=(select max(id) from collab_requirement_progress_event where requirement_id=?),last_synced_at=now(3),last_error_code=null,attempts=0,time_updated=now(3) where requirement_id=? and lease_owner=?",
                revision, hash, json.writeValueAsString(map), id, id, owner);
        grantChatPermission(id, actor, doc, (String) row.get("chat_id"));
    }

    private void addTableUpdates(Map<String, String> updates, JsonNode table, List<List<String>> lines,
            int rows, int columns, List<JsonNode> blocks) {
        JsonNode cells = table.path("table").path("cells");
        if (cells.size() != rows * columns) throw new ReconcileException("Progress table cells changed");
        Map<String, String> textByParent = new java.util.HashMap<>();
        Map<String, String> currentText = new java.util.HashMap<>();
        for (JsonNode block : blocks) if (block.path("block_type").asInt() == 2)
        {
            textByParent.putIfAbsent(block.path("parent_id").asText(), block.path("block_id").asText());
            StringBuilder content = new StringBuilder();
            for (JsonNode element : block.path("text").path("elements")) content.append(element.path("text_run").path("content").asText(""));
            currentText.put(block.path("block_id").asText(), content.toString());
        }
        for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
            String cell = cells.path(r * columns + c).asText("");
            String text = textByParent.get(cell);
            if (text == null) throw new ReconcileException("Progress cell text block missing");
            String value = r < lines.size() && c < lines.get(r).size() ? lines.get(r).get(c) : "";
            if (!value.equals(currentText.get(text))) updates.put(text, value);
        }
    }

    private JsonNode findExisting(long actor, String space, String parent, String title) {
        String page = ""; JsonNode found = null;
        do {
            JsonNode data = gateway.wikiChildren(actor, space, parent, page);
            for (JsonNode item : data.path("items")) if (title.equals(item.path("title").asText())) {
                if (found != null) throw new ReconcileException("Multiple progress documents have the same title");
                found = item;
            }
            if (!data.path("has_more").asBoolean(false)) return found;
            page = data.path("page_token").asText("");
            if (page.isBlank()) throw new ReconcileException("Wiki pagination is incomplete");
        } while (true);
    }

    private void persistNode(long id, String space, String parent, String node, String doc, String url) {
        jdbc.update("update collab_requirement_progress_doc set wiki_space_id=?,wiki_parent_node_token=?,wiki_node_token=?,document_token=?,document_url=?,status='WRITING',time_updated=now(3) where requirement_id=? and document_token is null",
                space, parent, node, doc, url, id);
    }

    private void grantChatPermission(long id, long actor, String doc, String chat) {
        if (chat == null || chat.isBlank()) return;
        String current = jdbc.queryForObject("select chat_permission_status from collab_requirement_progress_doc where requirement_id=?", String.class, id);
        if ("GRANTED".equals(current)) return;
        try {
            documents.grantChatReadAccess(actor, doc, "docx", chat);
            jdbc.update("update collab_requirement_progress_doc set chat_permission_status='GRANTED',chat_permission_last_attempt_at=now(3) where requirement_id=?", id);
        } catch (Exception ex) {
            jdbc.update("update collab_requirement_progress_doc set chat_permission_status='ERROR',chat_permission_last_attempt_at=now(3) where requirement_id=?", id);
            log.warn("[Yanshu][CollabProgress] chat permission pending requirementId={}", id);
        }
    }

    private void fail(long id, Exception ex) {
        String code = ex instanceof ReconcileException ? "RECONCILE_REQUIRED"
                : ex instanceof BusinessException be ? be.getCode() : "FEISHU_PROGRESS_ERROR";
        String previous = jdbc.queryForObject("select status from collab_requirement_progress_doc where requirement_id=?", String.class, id);
        String status = "FEISHU_AUTH_REQUIRED".equals(code) ? "AUTH_REQUIRED"
                : "CREATING".equals(previous) || "RECONCILE_REQUIRED".equals(code) || "FEISHU_PROGRESS_ERROR".equals(code) ? "RECONCILE_REQUIRED" : "ERROR";
        jdbc.update("update collab_requirement_progress_doc set status=?,last_error_code=?,attempts=attempts+1,time_updated=now(3) where requirement_id=? and lease_owner=?", status, code, id, owner);
        log.warn("[Yanshu][CollabProgress] projection failed requirementId={} code={}", id, code, ex);
    }

    private static String rootText(List<JsonNode> blocks, String parent) {
        for (JsonNode block : blocks) if (block.path("block_type").asInt() == 2 && parent.equals(block.path("parent_id").asText())) return block.path("block_id").asText();
        return null;
    }
    private static List<JsonNode> rootTables(List<JsonNode> blocks, String parent) {
        return blocks.stream().filter(b -> b.path("block_type").asInt() == 31 && parent.equals(b.path("parent_id").asText())).toList();
    }
    private static List<JsonNode> rootChildren(List<JsonNode> blocks, String parent) {
        return blocks.stream().filter(b -> parent.equals(b.path("parent_id").asText())).toList();
    }
    private static String sha(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private static class ReconcileException extends RuntimeException { ReconcileException(String message) { super(message); } }
}
