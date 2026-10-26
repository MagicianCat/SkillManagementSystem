package com.company.skillplatform.external;

import com.company.skillplatform.skill.domain.DevelopmentStage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** External stable-key view of the currently selectable development taxonomy. */
@Service
public class DevelopmentStageCatalogService {
    private static final String LIST_SQL = """
            SELECT root.category_key AS stageKey, root.category_name AS stageName, root.sort_order AS stageOrder,
                   leaf.category_key AS subStageKey, leaf.category_name AS subStageName, leaf.sort_order AS subStageOrder
            FROM skill_category root
            LEFT JOIN skill_category leaf ON leaf.parent_id = root.id AND leaf.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM skill_category child WHERE child.parent_id = leaf.id AND child.status = 'ACTIVE')
            WHERE root.parent_id IS NULL AND root.status = 'ACTIVE'
            ORDER BY root.sort_order, root.category_name, leaf.sort_order, leaf.category_name
            """;
    private static final String FIND_LEAF_SQL = """
            SELECT leaf.category_key AS subStageKey, root.category_key AS stageKey
            FROM skill_category leaf
            JOIN skill_category root ON root.id = leaf.parent_id AND root.parent_id IS NULL AND root.status = 'ACTIVE'
            WHERE leaf.category_key = ? AND leaf.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM skill_category child WHERE child.parent_id = leaf.id AND child.status = 'ACTIVE')
            """;
    private final JdbcTemplate db;

    public DevelopmentStageCatalogService(JdbcTemplate db) { this.db = db; }

    @Transactional(readOnly = true)
    public Dictionary list() {
        Map<String, StageBuilder> stages = new LinkedHashMap<>();
        for (Map<String, Object> row : db.queryForList(LIST_SQL)) {
            String stageKey = (String) row.get("stageKey");
            DevelopmentStage stage = stageFromRoot(stageKey);
            StageBuilder builder = stages.computeIfAbsent(stageKey,
                    ignored -> new StageBuilder(stage.name(), (String) row.get("stageName"), stageKey));
            if (row.get("subStageKey") != null) builder.subStages().add(new SubStage(
                    (String) row.get("subStageKey"), (String) row.get("subStageName")));
        }
        return new Dictionary(new ArrayList<>(stages.values().stream().map(StageBuilder::build).toList()));
    }

    @Transactional(readOnly = true)
    public ResolvedStage resolve(String rawStage, String rawSubStageKey) {
        String normalizedStage = rawStage == null || rawStage.isBlank() ? null : rawStage.trim();
        String subStageKey = rawSubStageKey == null || rawSubStageKey.isBlank() ? null : rawSubStageKey.trim();
        DevelopmentStage declared;
        try { declared = DevelopmentStage.fromNullable(normalizedStage); }
        catch (RuntimeException invalid) { throw new StageValidationException("INVALID_DEVELOPMENT_STAGE"); }
        if (subStageKey == null) return new ResolvedStage(declared == null ? null : declared.name(), null);
        List<Map<String, Object>> rows = db.queryForList(FIND_LEAF_SQL, subStageKey);
        if (rows.isEmpty()) throw new StageValidationException("INVALID_SUB_STAGE");
        DevelopmentStage derived = stageFromRoot((String) rows.get(0).get("stageKey"));
        if (declared != null && declared != derived) throw new StageValidationException("STAGE_CATEGORY_MISMATCH");
        return new ResolvedStage(derived.name(), subStageKey);
    }

    private DevelopmentStage stageFromRoot(String key) {
        try { return DevelopmentStage.fromRootCategoryKey(key); }
        catch (RuntimeException invalidTree) { throw new IllegalStateException("Invalid development taxonomy root: " + key, invalidTree); }
    }

    public record Dictionary(List<Stage> stages) {}
    public record Stage(String developmentStage, String stageName, String stageKey, List<SubStage> subStages) {}
    public record SubStage(String subStageKey, String subStageName) {}
    public record ResolvedStage(String developmentStage, String subStageKey) {}
    private record StageBuilder(String developmentStage, String stageName, String stageKey, List<SubStage> subStages) {
        StageBuilder(String developmentStage, String stageName, String stageKey) { this(developmentStage, stageName, stageKey, new ArrayList<>()); }
        Stage build() { return new Stage(developmentStage, stageName, stageKey, List.copyOf(subStages)); }
    }
    public static class StageValidationException extends RuntimeException {
        private final String errorCode;
        StageValidationException(String errorCode) { super(errorCode); this.errorCode = errorCode; }
        public String errorCode() { return errorCode; }
    }
}
