package com.company.skillplatform.collabproject.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared presentation for collaboration cards; callbacks remain owned by their senders. */
public final class CollabFeishuCardLayout {
    private CollabFeishuCardLayout() {}

    public static Map<String, Object> markdown(String content) {
        return Map.of("tag", "markdown", "content", content);
    }

    public static Map<String, Object> card(String title, String color, List<Object> elements) {
        return Map.of("schema", "2.0", "header", Map.of("template", color,
                "title", Map.of("tag", "plain_text", "content", title)),
                "body", Map.of("elements", elements));
    }

    public static String roster(List<Map<String, Object>> people, String fallbackRole) {
        Map<String, LinkedHashSet<String>> roles = new LinkedHashMap<>();
        for (Map<String, Object> person : people) {
            Object role = person.get("role_name");
            String name = role == null || role.toString().isBlank() ? fallbackRole : role.toString();
            Object id = person.get("feishu_open_id");
            String mention = id == null || id.toString().isBlank()
                    ? escape(String.valueOf(person.get("display_name"))) : "<at id=" + id + "></at>";
            roles.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(mention);
        }
        List<String> lines = new ArrayList<>();
        roles.forEach((role, members) -> lines.add(escape(role) + "：" + String.join("、", members)));
        return String.join("\n", lines);
    }

    public static String owners(JdbcTemplate jdbc, long stageId, boolean requirement) {
        List<Map<String, Object>> people = requirement
                ? jdbc.queryForList("SELECT DISTINCT rr.role_name_snapshot role_name,u.display_name,u.feishu_open_id "
                    + "FROM collab_requirement_stage_actor a JOIN collab_stage s ON s.id=a.stage_id "
                    + "JOIN collab_project p ON p.id=s.project_id "
                    + "JOIN collab_group_member gm ON gm.group_id=p.group_id AND gm.user_id=a.user_id AND gm.status='ACTIVE' "
                    + "JOIN iam_user u ON u.id=a.user_id AND u.status='ACTIVE' "
                    + "LEFT JOIN collab_requirement_stage_role sr ON sr.stage_id=a.stage_id AND sr.duty='OWNER' "
                    + "LEFT JOIN collab_requirement_role rr ON rr.id=sr.requirement_role_id "
                    + "WHERE a.stage_id=? AND a.duty='OWNER' AND a.frozen_at IS NOT NULL "
                    + "ORDER BY rr.role_name_snapshot,u.display_name", stageId)
                : jdbc.queryForList("SELECT u.display_name,u.feishu_open_id FROM collab_stage s "
                    + "JOIN iam_user u ON u.id=s.owner_user_id WHERE s.id=?", stageId);
        return roster(people, "阶段负责人");
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_");
    }
}
