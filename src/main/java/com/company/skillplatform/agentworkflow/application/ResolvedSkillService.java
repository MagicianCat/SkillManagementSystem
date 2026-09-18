package com.company.skillplatform.agentworkflow.application;

import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves mutable Skill bindings into immutable runtime snapshots. */
@Service
public class ResolvedSkillService {
    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;

    public ResolvedSkillService(JdbcTemplate jdbc, ObjectStoragePort storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public List<Map<String, Object>> resolve(long profileVersionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select s.skill_key,b.required,"
                + "coalesce(b.fixed_skill_version_id,s.latest_published_version_id) version_id "
                + "from agent_profile_version_skill b join skill s on s.id=b.skill_id "
                + "where b.agent_profile_version_id=? order by b.sort_order", profileVersionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object versionId = row.get("version_id");
            if (!(versionId instanceof Number number)) {
                if (Boolean.TRUE.equals(row.get("required"))) throw new IllegalStateException("Required Skill has no published version: " + row.get("skill_key"));
                continue;
            }
            Map<String, Object> version = jdbc.queryForMap("select version,source_sha256,source_object_key from skill_version where id=? and lifecycle_status in ('PUBLISHED','DEPRECATED')", number.longValue());
            result.add(Map.of("name", String.valueOf(row.get("skill_key")), "versionId", number.longValue(),
                    "version", String.valueOf(version.get("version")), "sha256", String.valueOf(version.get("source_sha256")),
                    "content", skillMarkdown(String.valueOf(version.get("source_object_key"))),
                    "required", Boolean.TRUE.equals(row.get("required"))));
        }
        return result;
    }

    private String skillMarkdown(String objectKey) {
        try (InputStream source = storage.get(objectKey); ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String normalized = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && (normalized.equalsIgnoreCase("SKILL.md") || normalized.endsWith("/SKILL.md"))) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    zip.transferTo(output);
                    return output.toString(StandardCharsets.UTF_8);
                }
            }
            throw new IllegalStateException("Skill archive does not contain SKILL.md");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot materialize Skill archive", failure);
        }
    }
}
