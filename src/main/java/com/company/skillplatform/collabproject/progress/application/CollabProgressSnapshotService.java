package com.company.skillplatform.collabproject.progress.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads business truth for a document projection; never changes workflow state. */
@Service
public class CollabProgressSnapshotService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private final JdbcTemplate jdbc;
    private final String webBase;

    public CollabProgressSnapshotService(JdbcTemplate jdbc,
            @Value("${collab.web-base-url:http://127.0.0.1:5173}") String webBase) {
        this.jdbc = jdbc; this.webBase = webBase.replaceAll("/$", "");
    }

    public Snapshot read(long requirementId) {
        Map<String, Object> project = jdbc.queryForMap("select p.project_key,p.name,p.status,p.approval_level,g.name group_name,u.display_name owner_name from collab_project p join collab_group g on g.id=p.group_id join iam_user u on u.id=p.owner_user_id where p.id=?", requirementId);
        List<Map<String, Object>> stages = jdbc.queryForList("select id,stage_key,stage_order,title,status,attempt_no,time_updated from collab_stage where project_id=? order by stage_order", requirementId);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("阶段", "负责人", "当前状态／轮次", "产物", "审批进展", "最新时间", "入口"));
        int completed = 0, included = 0;
        List<String> current = new ArrayList<>();
        for (Map<String, Object> stage : stages) {
            long stageId = ((Number) stage.get("id")).longValue();
            String status = String.valueOf(stage.get("status"));
            if (!"SKIPPED".equals(status)) included++;
            if ("COMPLETED".equals(status)) completed++;
            if (List.of("IN_PROGRESS", "REWORK", "PUBLISHING", "PENDING_APPROVAL", "READY_BLOCKED").contains(status)) current.add(String.valueOf(stage.get("title")));
            List<String> owners = jdbc.query("select distinct u.display_name from collab_requirement_stage_actor a join iam_user u on u.id=a.user_id where a.stage_id=? and a.duty='OWNER' order by u.display_name",
                    (rs, n) -> rs.getString(1), stageId);
            String owner = owners.isEmpty() ? "待确定" : String.join("、", owners);
            String state = statusLabel(status) + ("SKIPPED".equals(status) || "WAITING".equals(status) ? "" : " / 第 " + stage.get("attempt_no") + " 轮");
            Integer published = jdbc.queryForObject("select count(*) from collab_feishu_publication pub join collab_artifact_version v on v.id=pub.artifact_version_id where v.stage_id=? and pub.publish_status='PUBLISHED'", Integer.class, stageId);
            Integer asdmFiles = jdbc.queryForObject("select count(*) from collab_asdm_package_file f join collab_asdm_package p on p.id=f.package_id where p.stage_id=? and p.package_status='PUBLISHED' and f.publish_status='PUBLISHED'", Integer.class, stageId);
            Integer archivedFiles = jdbc.queryForObject("select count(*) from collab_asdm_published_file_registry where stage_id=?", Integer.class, stageId);
            int fileCount = Math.max(Math.max(published == null ? 0 : published, asdmFiles == null ? 0 : asdmFiles), archivedFiles == null ? 0 : archivedFiles);
            String artifact = fileCount > 0 ? "已发布 · " + fileCount + " 份" : "—";
            List<Map<String, Object>> submissions = jdbc.queryForList("select id,status from collab_submission where stage_id=? order by id desc limit 1", stageId);
            String approval = approvalLabel(status, submissions);
            String when = stage.get("time_updated") == null ? "—" : TIME.format(instant(stage.get("time_updated")));
            String url = webBase + "/collab-requirements/" + project.get("project_key") + "/stages/" + stage.get("stage_key");
            List<String> publicationUrls = jdbc.query("select pub.url from collab_feishu_publication pub join collab_artifact_version v on v.id=pub.artifact_version_id where v.stage_id=? and pub.publish_status='PUBLISHED' and pub.url is not null order by pub.published_at desc limit 1", (rs, n) -> rs.getString(1), stageId);
            String entry = publicationUrls.isEmpty() ? url : url + "  |  飞书产物：" + publicationUrls.get(0);
            rows.add(List.of(String.format("%02d %s", ((Number) stage.get("stage_order")).intValue(), stage.get("title")), owner, state, artifact, approval, when, entry));
        }
        List<List<String>> events = new ArrayList<>();
        events.add(List.of("时间", "阶段／轮次", "事件", "操作人", "说明／审批意见", "链接"));
        for (Map<String, Object> row : jdbc.queryForList("select e.occurred_at,e.stage_id,e.attempt_no,e.event_type,e.summary,e.target_url,u.display_name actor_name,s.title stage_title,s.stage_key from collab_requirement_progress_event e left join iam_user u on u.id=e.actor_user_id left join collab_stage s on s.id=e.stage_id where e.requirement_id=? order by e.occurred_at,e.id", requirementId)) {
            String stage = row.get("stage_title") == null ? "全局" : String.valueOf(row.get("stage_title")) + (row.get("attempt_no") == null ? "" : " / " + row.get("attempt_no"));
            String summary = Objects.toString(row.get("summary"), "");
            if (summary.length() > 180) summary = summary.substring(0, 180) + "…（详情见研枢）";
            String target = Objects.toString(row.get("target_url"), "");
            if (target.isBlank()) target = webBase + "/collab-requirements/" + project.get("project_key")
                    + (row.get("stage_key") == null ? "" : "/stages/" + row.get("stage_key"));
            events.add(List.of(TIME.format(instant(row.get("occurred_at"))), stage,
                    eventLabel(String.valueOf(row.get("event_type"))), Objects.toString(row.get("actor_name"), "系统／来源记录"),
                    summary, target));
        }
        String overview = "项目组：" + project.get("group_name") + "  需求：" + project.get("name") + "  负责人：" + project.get("owner_name")
                + "  状态：" + ("COMPLETED".equals(project.get("status")) ? "已完成" : "进行中")
                + "  当前阶段：" + (current.isEmpty() ? "—" : String.join("、", current))
                + "  进度：" + completed + "/" + included + "（" + (included == 0 ? 0 : completed * 100 / included) + "%）"
                + "  审批策略：" + project.get("approval_level") + "  需求入口：" + webBase + "/collab-requirements/" + project.get("project_key");
        return new Snapshot(overview, rows, events);
    }

    private String approvalLabel(String stageStatus, List<Map<String, Object>> submissions) {
        if (submissions.isEmpty()) return "未提交";
        Map<String, Object> sub = submissions.get(0);
        if ("PUBLISHING".equals(stageStatus)) return "产物同步中";
        if ("REWORK".equals(stageStatus)) return "上轮驳回";
        if ("PENDING_APPROVAL".equals(stageStatus)) {
            long id = ((Number) sub.get("id")).longValue();
            Integer total = jdbc.queryForObject("select count(*) from collab_submission_reviewer_role where submission_id=?", Integer.class, id);
            if (total != null && total > 0) {
                Integer satisfied = jdbc.queryForObject("select count(*) from collab_submission_reviewer_role r where r.submission_id=? and exists(select 1 from collab_submission_reviewer_assignment a join collab_approval ap on ap.id=a.approval_id where a.submission_id=r.submission_id and a.requirement_role_id=r.requirement_role_id and ap.state='APPROVED')", Integer.class, id);
                return "审批角色已满足 " + satisfied + "/" + total;
            }
            return "审批中";
        }
        if ("COMPLETED".equals(stageStatus)) {
            Integer approved = jdbc.queryForObject("select count(*) from collab_approval where submission_id=? and state='APPROVED'", Integer.class, sub.get("id"));
            return approved != null && approved > 0 ? "人工审批通过" : "自动通过（无需人工审批）";
        }
        return "—";
    }

    private static String statusLabel(String value) {
        return switch (value) {
            case "WAITING", "READY", "NOT_STARTED" -> "待开始";
            case "READY_BLOCKED" -> "待补齐人员";
            case "IN_PROGRESS" -> "进行中";
            case "REWORK" -> "修改中";
            case "PUBLISHING" -> "产物同步飞书中";
            case "PENDING_APPROVAL" -> "审批中";
            case "COMPLETED" -> "已完成";
            case "SKIPPED" -> "不参与";
            default -> value;
        };
    }

    private static String eventLabel(String type) {
        return switch (type) {
            case "REQUIREMENT_CREATED" -> "需求创建";
            case "STAGE_ACTIVATED", "ASSIGNMENT_RESTORED" -> "阶段启动";
            case "STAGE_COMPLETED" -> "阶段完成";
            case "ARTIFACT_SUBMITTED" -> "产物提交";
            case "ARTIFACT_PUBLISHED" -> "飞书发布成功";
            case "APPROVAL_DECISION", "APPROVED", "REVIEWER_APPROVED" -> "审批通过";
            case "APPROVAL_REJECTED", "REJECTED" -> "审批驳回";
            case "STAGE_AUTO_APPROVED" -> "自动通过";
            case "ARTIFACT_SUPPLEMENTED" -> "补充归档";
            case "ASSIGNMENT_BLOCKED" -> "阶段启动受阻";
            case "REQUIREMENT_COMPLETED" -> "需求完成";
            default -> type;
        };
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        return (Instant) value;
    }

    public record Snapshot(String overview, List<List<String>> stages, List<List<String>> events) {}
}
