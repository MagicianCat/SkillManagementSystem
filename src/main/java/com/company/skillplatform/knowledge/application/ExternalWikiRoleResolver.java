package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.knowledge.domain.ExternalRoleMetadata;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ExternalWikiRoleResolver {
    public static final String SOURCE_MATCH = """
            d.source_system=r.source_system AND d.source_resource_id=r.external_resource_id
            AND ((r.resource_type='DOCUMENT' AND d.source_entry_path IS NOT NULL AND d.source_entry_path<>'ASDM-TOOL')
              OR (r.resource_type='ASDM-TOOL' AND d.source_entry_path='ASDM-TOOL'))
            """;
    private final NamedParameterJdbcTemplate db;
    public ExternalWikiRoleResolver(NamedParameterJdbcTemplate db) { this.db = db; }

    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public ExternalRoleMetadata resolve(long id) { return resolveAll(List.of(id)).get(id); }

    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public ExternalRoleMetadata resolveForIndex(long id) {
        Integer active = db.queryForObject(
                "SELECT COUNT(*) FROM wiki_document WHERE id=:id AND status='ACTIVE'",
                Map.of("id", id), Integer.class);
        return active == null || active == 0 ? null : resolveAll(List.of(id)).get(id);
    }

    public Map<Long, ExternalRoleMetadata> resolveAll(Collection<Long> ids) {
        List<Long> documents = ids.stream().distinct().toList();
        Map<Long, ExternalRoleMetadata> result = new HashMap<>();
        for (int start=0; start<documents.size(); start+=200) {
            List<Long> batch = documents.subList(start, Math.min(start+200, documents.size()));
            batch.forEach(id -> result.put(id, ExternalRoleMetadata.LOCAL));
            Map<Long, List<ExternalRoleMetadata.Role>> roles = new HashMap<>();
            db.query("""
                    SELECT d.id, d.source_system, d.source_resource_id, r.resource_type,
                           er.id role_id, er.role_name, er.external_role_id
                    FROM wiki_document d LEFT JOIN external_resource r ON
                    """ + SOURCE_MATCH + """
                    LEFT JOIN external_role_resource rr ON rr.resource_id=r.id
                    LEFT JOIN external_role er ON er.id=rr.role_id AND er.status='ACTIVE'
                    WHERE d.id IN (:ids) AND d.status='ACTIVE' AND d.source_system IS NOT NULL
                    ORDER BY d.id, er.id
                    """, Map.of("ids", batch), rs -> {
                long id = rs.getLong("id");
                String type = rs.getString("resource_type");
                if (result.get(id).equals(ExternalRoleMetadata.LOCAL)) {
                    result.put(id, new ExternalRoleMetadata(rs.getString("source_system"),
                            rs.getString("source_resource_id"), type, List.of()));
                    if (type == null) org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                            "event=external_wiki_source_unresolved documentId={}", id);
                }
                long roleId = rs.getLong("role_id");
                if (!rs.wasNull()) roles.computeIfAbsent(id, ignored -> new ArrayList<>()).add(
                        new ExternalRoleMetadata.Role(roleId, rs.getString("role_name"), rs.getString("external_role_id")));
            });
            roles.forEach((id, refs) -> {
                var source = result.get(id);
                result.put(id, new ExternalRoleMetadata(source.sourceSystem(), source.sourceResourceId(), source.resourceType(), refs.stream().distinct().toList()));
            });
        }
        return result;
    }
}
