package com.company.skillplatform.telemetry.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves a command's imported toolset category once, when the event is first stored. */
@Component
public class AsdmCommandCategoryResolver {
    private final JdbcTemplate jdbc;

    public AsdmCommandCategoryResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Resolution(Long assetId, Long mappingId, String firstCategoryCode,
                             String developmentStage, String matchStatus) {}

    public Resolution resolve(Long assetId, String toolsetName) {
        if (assetId == null) return new Resolution(null, null, null, null, "NO_ASSET");
        if (toolsetName == null || toolsetName.isBlank())
            return new Resolution(assetId, null, null, null, "NO_TOOLSET");
        List<String> ready = jdbc.query("SELECT status FROM external_asdm_tool_asset WHERE id=?",
                (rs, row) -> rs.getString(1), assetId);
        if (ready.isEmpty() || !"READY".equals(ready.get(0)))
            return new Resolution(assetId, null, null, null, "ASSET_UNKNOWN");
        List<Resolution> matches = jdbc.query("""
                SELECT id, first_category_code, development_stage
                FROM external_asdm_command_category
                WHERE asset_id=? AND tool_name=? AND mapping_status='ACTIVE'
                """, (rs, row) -> new Resolution(assetId, rs.getLong(1), rs.getString(2), rs.getString(3), "MATCHED"),
                assetId, toolsetName);
        return matches.isEmpty() ? new Resolution(assetId, null, null, null, "TOOLSET_UNMAPPED") : matches.get(0);
    }
}
