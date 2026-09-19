package com.company.skillplatform.agentworkflow.application;

import com.company.skillplatform.storage.domain.ObjectStoragePort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        List<Map<String, Object>> rows = jdbc.queryForList("select s.id skill_id,s.skill_key,b.required,b.version_policy,b.fixed_skill_version_id,s.latest_published_version_id "
                + "from agent_profile_version_skill b join skill s on s.id=b.skill_id "
                + "where b.agent_profile_version_id=? order by b.sort_order", profileVersionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String policy=String.valueOf(row.get("version_policy")); Object versionId="FIXED".equals(policy)?row.get("fixed_skill_version_id"):"LATEST_PUBLISHED".equals(policy)?row.get("latest_published_version_id"):null;
            if (!Set.of("FIXED","LATEST_PUBLISHED").contains(policy)) throw new IllegalStateException("Unknown Skill version policy: "+policy);
            if ("FIXED".equals(policy) && versionId == null) throw new IllegalStateException("FIXED Skill requires fixed version: "+row.get("skill_key"));
            if (!(versionId instanceof Number number)) {
                if (Boolean.TRUE.equals(row.get("required"))) throw new IllegalStateException("Required Skill has no published version: " + row.get("skill_key"));
                continue;
            }
            Map<String, Object> version = jdbc.queryForMap("select version,source_sha256,source_object_key,skill_id from skill_version where id=? and lifecycle_status in ('PUBLISHED','DEPRECATED')", number.longValue());
            if (!Objects.equals(((Number)version.get("skill_id")).longValue(),((Number)row.get("skill_id")).longValue())) throw new IllegalStateException("Fixed Skill version does not belong to Skill: "+row.get("skill_key"));
            result.add(Map.of("name", String.valueOf(row.get("skill_key")), "versionId", number.longValue(),
                    "version", String.valueOf(version.get("version")), "sha256", String.valueOf(version.get("source_sha256")),
                    "content", skillMarkdown(String.valueOf(version.get("source_object_key"))),
                    "required", Boolean.TRUE.equals(row.get("required"))));
        }
        return result;
    }

    public List<Map<String, Object>> resolve(long profileVersionId, long workflowRunId) {
        List<Map<String, Object>> frozen = jdbc.queryForList("select s.skill_key,x.skill_version_id,x.required,v.version,v.source_sha256,v.source_object_key "
                + "from workflow_run_skill_snapshot x join skill s on s.id=x.skill_id join skill_version v on v.id=x.skill_version_id "
                + "where x.workflow_run_id=? and x.agent_profile_version_id=? order by x.sort_order", workflowRunId, profileVersionId);
        if (frozen.isEmpty()) {Long configured=jdbc.queryForObject("select project_agent_configuration_id from workflow_run where id=?",Long.class,workflowRunId);if(configured!=null)return List.of();return resolve(profileVersionId);}
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : frozen) result.add(Map.of("name", String.valueOf(row.get("skill_key")),
                "versionId", ((Number) row.get("skill_version_id")).longValue(), "version", String.valueOf(row.get("version")),
                "sha256", String.valueOf(row.get("source_sha256")), "content", skillMarkdown(String.valueOf(row.get("source_object_key"))),
                "required", Boolean.TRUE.equals(row.get("required"))));
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
