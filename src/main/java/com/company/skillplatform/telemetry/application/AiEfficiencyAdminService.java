package com.company.skillplatform.telemetry.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.telemetry.domain.GenerationStage;
import com.company.skillplatform.user.infrastructure.repository.OrgTeamRepository;
import com.company.skillplatform.user.infrastructure.repository.ScopedRoleAssignmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 研发效能看板的只读统计服务。以 ai_generation_event 为事实中心：
 * Token/代码量/耗时属于 Generation，Skill 仅与之关联（避免多 Skill 重复求和）。
 * 只有 token_quality ∈ (EXACT, PARTIAL) 的 Generation 参与 Token 统计。
 */
@Service
public class AiEfficiencyAdminService {
    /** 参与 Token 统计的质量口径。 */
    private static final String COUNTABLE_QUALITY = "('EXACT','PARTIAL')";

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopedRoleAssignmentRepository assignments;
    private final OrgTeamRepository teams;
    private final Clock clock = Clock.systemUTC();

    public AiEfficiencyAdminService(NamedParameterJdbcTemplate jdbc,
            ScopedRoleAssignmentRepository assignments, OrgTeamRepository teams) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.teams = teams;
    }

    // ---- Dashboard ----

    @Transactional(readOnly = true)
    public Dashboard dashboard(Long actorId, boolean globalAdmin, Filters filters) {
        Filters effective = effectiveFilters(filters);
        Summary summary = summary(actorId, globalAdmin, effective, null);
        List<TrendPoint> trend = trend(actorId, globalAdmin, effective, null);
        List<DimensionEfficiency> stageRanking = stageRanking(actorId, globalAdmin, effective);
        List<DimensionEfficiency> teamRanking = teamRanking(actorId, globalAdmin, effective, null);
        List<DimensionEfficiency> projectRanking = projectRanking(actorId, globalAdmin, effective, null);
        List<SkillEfficiency> skillRanking = skillRanking(actorId, globalAdmin, effective, null, 20);
        List<FileTypeEfficiency> fileTypes = fileTypes(actorId, globalAdmin, effective, null);
        TokenBreakdown tokens = tokenBreakdown(actorId, globalAdmin, effective, null);
        return new Dashboard(summary, trend, stageRanking, teamRanking, projectRanking, skillRanking,
                fileTypes, tokens, effective.from(), effective.to());
    }

    // ---- Generation 明细 ----

    @Transactional(readOnly = true)
    public GenerationPage generations(Long actorId, boolean globalAdmin, Filters filters, int page, int size) {
        Filters effective = effectiveFilters(filters);
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.min(Math.max(size, 1), 100);
        QueryParts countParts = queryParts(actorId, globalAdmin, effective, null);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + countParts.where(),
                countParts.params(), Long.class);
        QueryParts rowsParts = queryParts(actorId, globalAdmin, effective, null);
        rowsParts.params().addValue("limit", boundedSize).addValue("offset", boundedPage * boundedSize);
        List<GenerationRow> items = jdbc.query(
                "SELECT e.id, e.hook_generation_id, e.started_at, e.user_id, u.display_name, u.username, " +
                        "e.project_key, e.project_name, e.primary_stage, e.status, e.lines_added, e.lines_deleted, " +
                        "e.total_tokens, e.token_quality, e.duration_ms, e.model_call_count, e.tool_call_count, e.tool_failure_count, " +
                        "(SELECT GROUP_CONCAT(DISTINCT s.skill_key ORDER BY s.skill_key SEPARATOR ',') FROM ai_generation_skill gs " +
                        " JOIN skill s ON s.id=gs.skill_id WHERE gs.generation_id=e.id) skill_keys, " +
                        "(SELECT GROUP_CONCAT(DISTINCT t.team_name ORDER BY t.team_name SEPARATOR ', ') FROM org_team_member tm " +
                        " JOIN org_team t ON t.id=tm.team_id WHERE tm.user_id=e.user_id AND tm.status='ACTIVE') team_names " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + rowsParts.where() +
                        " ORDER BY e.started_at DESC, e.id DESC LIMIT :limit OFFSET :offset",
                rowsParts.params(), this::generationRow);
        int totalPages = total == 0 ? 0 : (int) ((total + boundedSize - 1) / boundedSize);
        return new GenerationPage(items, boundedPage, boundedSize, total, totalPages);
    }

    // ---- 详情页 ----

    @Transactional(readOnly = true)
    public StageDetail stage(Long actorId, boolean globalAdmin, String stageRaw, Filters filters) {
        GenerationStage stage = GenerationStage.fromNullable(stageRaw);
        if (stage == null) throw error("STAGE_REQUIRED", "Stage is required", HttpStatus.BAD_REQUEST);
        Filters effective = effectiveFilters(filters);
        String condition = " AND e.primary_stage=:detailStage";
        Summary summary = summary(actorId, globalAdmin, effective, new DetailFilter(condition, p -> p.addValue("detailStage", stage.name())));
        List<TrendPoint> trend = trend(actorId, globalAdmin, effective, new DetailFilter(condition, p -> p.addValue("detailStage", stage.name())));
        List<DimensionEfficiency> teamRanking = teamRanking(actorId, globalAdmin, effective, new DetailFilter(condition, p -> p.addValue("detailStage", stage.name())));
        List<DimensionEfficiency> projectRanking = projectRanking(actorId, globalAdmin, effective, new DetailFilter(condition, p -> p.addValue("detailStage", stage.name())));
        List<SkillEfficiency> skillRanking = skillRanking(actorId, globalAdmin, effective, new DetailFilter(condition, p -> p.addValue("detailStage", stage.name())), 20);
        List<FileTypeEfficiency> fileTypes = fileTypes(actorId, globalAdmin, effective, new DetailFilter(condition, p -> p.addValue("detailStage", stage.name())));
        long projectCount = scalarLong(actorId, globalAdmin, effective, new DetailFilter(condition, p -> p.addValue("detailStage", stage.name())),
                "COUNT(DISTINCT e.project_key)");
        return new StageDetail(stage.name(), summary, projectCount, trend, teamRanking, projectRanking, skillRanking,
                fileTypes, effective.from(), effective.to());
    }

    @Transactional(readOnly = true)
    public TeamDetail team(Long actorId, boolean globalAdmin, Long teamId, Filters filters) {
        if (!globalAdmin && !isManagedTeam(actorId, teamId))
            throw new AccessDeniedException("Team administrator permission required");
        if (!teamExists(teamId)) throw error("TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND);
        Filters effective = effectiveFilters(filters);
        // 团队详情：把范围强制收缩到该团队（全局管理员也只看这个团队）。
        Filters teamScoped = new Filters(effective.from(), effective.to(), teamId, effective.userId());
        String teamName = teams.findById(teamId).map(t -> t.getTeamName()).orElse("unknown");
        Summary summary = summary(actorId, globalAdmin, teamScoped, null);
        List<TrendPoint> trend = trend(actorId, globalAdmin, teamScoped, null);
        List<MemberEfficiency> members = memberRanking(actorId, globalAdmin, teamScoped);
        List<DimensionEfficiency> stageDistribution = stageRanking(actorId, globalAdmin, teamScoped);
        List<DimensionEfficiency> projectDistribution = projectRanking(actorId, globalAdmin, teamScoped, null);
        List<SkillEfficiency> skillDistribution = skillRanking(actorId, globalAdmin, teamScoped, null, 20);
        return new TeamDetail(teamId, teamName, summary, trend, members, stageDistribution, projectDistribution,
                skillDistribution, effective.from(), effective.to());
    }

    @Transactional(readOnly = true)
    public ProjectDetail project(Long actorId, boolean globalAdmin, String projectKey, Filters filters) {
        Filters effective = effectiveFilters(filters);
        String condition = " AND e.project_key=:projectKey";
        DetailFilter detail = new DetailFilter(condition, p -> p.addValue("projectKey", projectKey));
        Summary summary = summary(actorId, globalAdmin, effective, detail);
        if (summary.generations() == 0) throw error("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND);
        List<TrendPoint> trend = trend(actorId, globalAdmin, effective, detail);
        List<DimensionEfficiency> stageDistribution = stageRanking(actorId, globalAdmin, effective, detail);
        List<FileTypeEfficiency> fileTypes = fileTypes(actorId, globalAdmin, effective, detail);
        List<SkillEfficiency> skillRanking = skillRanking(actorId, globalAdmin, effective, detail, 20);
        List<MemberEfficiency> members = memberRanking(actorId, globalAdmin, effective, detail);
        String projectName = jdbc.query(
                "SELECT project_name FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " +
                        queryParts(actorId, globalAdmin, effective, detail).where() + " AND e.project_name IS NOT NULL " +
                        "GROUP BY e.project_name ORDER BY COUNT(*) DESC LIMIT 1",
                queryParts(actorId, globalAdmin, effective, detail).params(),
                (rs, row) -> rs.getString("project_name")).stream().findFirst().orElse(projectKey);
        Long skillCountValue = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT gs.skill_id) FROM ai_generation_skill gs " +
                        "WHERE gs.generation_id IN (SELECT e.id FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " +
                        queryParts(actorId, globalAdmin, effective, detail).where() + ")",
                queryParts(actorId, globalAdmin, effective, detail).params(), Long.class);
        long skillCount = skillCountValue == null ? 0 : skillCountValue;
        return new ProjectDetail(projectKey, projectName, summary, skillCount, trend, stageDistribution, fileTypes,
                skillRanking, members, effective.from(), effective.to());
    }

    @Transactional(readOnly = true)
    public SkillDetail skill(Long actorId, boolean globalAdmin, String skillKey, Filters filters) {
        Filters effective = effectiveFilters(filters);
        // Skill 详情：所有统计都通过 ai_generation_skill 关联回 Generation。
        String exists = " AND EXISTS (SELECT 1 FROM ai_generation_skill gs JOIN skill s2 ON s2.id=gs.skill_id " +
                "WHERE gs.generation_id=e.id AND s2.skill_key=:skillKey)";
        DetailFilter detail = new DetailFilter(exists, p -> p.addValue("skillKey", skillKey));
        SkillSummary summary = skillSummary(actorId, globalAdmin, effective, detail, skillKey);
        if (summary.invocations() == 0) throw error("SKILL_NOT_FOUND", "Skill not found", HttpStatus.NOT_FOUND);
        List<TrendPoint> trend = trend(actorId, globalAdmin, effective, detail);
        List<DimensionEfficiency> teamRanking = teamRanking(actorId, globalAdmin, effective, detail);
        List<DimensionEfficiency> projectRanking = projectRanking(actorId, globalAdmin, effective, detail);
        List<DimensionEfficiency> stageDistribution = stageRanking(actorId, globalAdmin, effective, detail);
        List<FileTypeEfficiency> fileTypes = fileTypes(actorId, globalAdmin, effective, detail);
        List<SkillCombo> combos = skillCombos(actorId, globalAdmin, effective, skillKey);
        return new SkillDetail(skillKey, summary, trend, teamRanking, projectRanking, stageDistribution, fileTypes,
                combos, effective.from(), effective.to());
    }

    // ---- 共享统计构件 ----

    private Summary summary(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        MapSqlParameterSource params = parts.params();
        var row = jdbc.queryForMap(
                "SELECT COUNT(*) generations, COUNT(DISTINCT e.user_id) active_users, " +
                        "COALESCE(SUM(e.lines_added),0) lines_added, COALESCE(SUM(e.lines_deleted),0) lines_deleted, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.input_tokens END),0) input_tokens, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.output_tokens END),0) output_tokens, " +
                        "SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN 1 ELSE 0 END) countable_generations, " +
                        "AVG(e.duration_ms) avg_duration_ms, AVG(e.model_call_count) avg_model_calls, " +
                        "SUM(e.tool_call_count) tool_calls, SUM(e.tool_failure_count) tool_failures " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where(), params);
        long generations = number(row.get("generations"));
        long totalTokens = number(row.get("total_tokens"));
        long linesAdded = number(row.get("lines_added"));
        long countable = number(row.get("countable_generations"));
        long toolCalls = number(row.get("tool_calls"));
        long toolFailures = number(row.get("tool_failures"));
        return new Summary(generations, number(row.get("active_users")), linesAdded, number(row.get("lines_deleted")),
                number(row.get("input_tokens")), number(row.get("output_tokens")), totalTokens,
                generations == 0 ? 0 : (double) countable / generations,
                totalTokens == 0 ? 0 : (double) linesAdded / totalTokens * 1000,
                row.get("avg_duration_ms") == null ? 0 : ((Number) row.get("avg_duration_ms")).doubleValue(),
                row.get("avg_model_calls") == null ? 0 : ((Number) row.get("avg_model_calls")).doubleValue(),
                toolCalls == 0 ? 0 : (double) toolFailures / toolCalls);
    }

    private List<TrendPoint> trend(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        String format = Duration.between(filters.from(), filters.to()).toHours() <= 48 ? "%Y-%m-%d %H:00:00" : "%Y-%m-%d";
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        return jdbc.query(
                "SELECT DATE_FORMAT(e.started_at, '" + format + "') bucket, COUNT(*) generations, " +
                        "COUNT(DISTINCT e.user_id) active_users, COALESCE(SUM(e.lines_added),0) lines_added, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where() +
                        " GROUP BY bucket ORDER BY bucket",
                parts.params(), (rs, row) -> new TrendPoint(rs.getString("bucket"), rs.getLong("generations"),
                        rs.getLong("active_users"), rs.getLong("lines_added"), rs.getLong("total_tokens")));
    }

    private List<DimensionEfficiency> stageRanking(Long actorId, boolean globalAdmin, Filters filters) {
        return stageRanking(actorId, globalAdmin, filters, null);
    }

    private List<DimensionEfficiency> stageRanking(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        return jdbc.query(
                "SELECT COALESCE(e.primary_stage,'UNKNOWN') dim_key, COALESCE(e.primary_stage,'UNKNOWN') dim_name, " +
                        "COUNT(*) generations, COUNT(DISTINCT e.user_id) users, COALESCE(SUM(e.lines_added),0) lines_added, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where() +
                        " GROUP BY dim_key ORDER BY lines_added DESC, dim_key LIMIT 20",
                parts.params(), this::dimensionEfficiency);
    }

    private List<DimensionEfficiency> teamRanking(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        return jdbc.query(
                "SELECT CAST(t.id AS CHAR) dim_key, t.team_name dim_name, COUNT(DISTINCT e.id) generations, " +
                        "COUNT(DISTINCT e.user_id) users, COALESCE(SUM(e.lines_added),0) lines_added, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " +
                        "JOIN org_team_member tm ON tm.user_id=e.user_id AND tm.status='ACTIVE' " +
                        "JOIN org_team t ON t.id=tm.team_id AND t.status='ACTIVE' " + parts.where() +
                        " GROUP BY t.id, t.team_name ORDER BY lines_added DESC, dim_name LIMIT 20",
                parts.params(), this::dimensionEfficiency);
    }

    private List<DimensionEfficiency> projectRanking(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        return jdbc.query(
                "SELECT e.project_key dim_key, COALESCE(MAX(e.project_name), e.project_key) dim_name, COUNT(*) generations, " +
                        "COUNT(DISTINCT e.user_id) users, COALESCE(SUM(e.lines_added),0) lines_added, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where() +
                        " AND e.project_key IS NOT NULL GROUP BY e.project_key ORDER BY lines_added DESC, dim_name LIMIT 20",
                parts.params(), this::dimensionEfficiency);
    }

    private List<SkillEfficiency> skillRanking(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail, int limit) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        MapSqlParameterSource params = parts.params();
        params.addValue("skillLimit", limit);
        // 关联 Generation 级指标，不跨 Skill 求和（一个 Generation 多 Skill 会重复）。
        return jdbc.query(
                "SELECT s.skill_key, MAX(s.display_name) display_name, COUNT(DISTINCT gs.generation_id) generations, " +
                        "SUM(gs.invocation_count) invocations, COUNT(DISTINCT e.user_id) users, " +
                        "COUNT(DISTINCT e.project_key) projects, MAX(e.started_at) last_invoked_at " +
                        "FROM ai_generation_skill gs JOIN skill s ON s.id=gs.skill_id " +
                        "JOIN ai_generation_event e ON e.id=gs.generation_id JOIN iam_user u ON u.id=e.user_id " +
                        parts.where() + " GROUP BY s.id, s.skill_key ORDER BY generations DESC, s.skill_key LIMIT :skillLimit",
                params, (rs, row) -> new SkillEfficiency(rs.getString("skill_key"), rs.getString("display_name"),
                        rs.getLong("generations"), rs.getLong("invocations"), rs.getLong("users"),
                        rs.getLong("projects"), instant(rs, "last_invoked_at")));
    }

    private List<MemberEfficiency> memberRanking(Long actorId, boolean globalAdmin, Filters filters) {
        return memberRanking(actorId, globalAdmin, filters, null);
    }

    private List<MemberEfficiency> memberRanking(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        return jdbc.query(
                "SELECT e.user_id, u.display_name, u.username, COUNT(*) generations, " +
                        "COALESCE(SUM(e.lines_added),0) lines_added, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens, " +
                        "MAX(e.started_at) last_active_at " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where() +
                        " GROUP BY e.user_id, u.display_name, u.username ORDER BY generations DESC, u.display_name LIMIT 100",
                parts.params(), (rs, row) -> new MemberEfficiency(rs.getLong("user_id"), rs.getString("display_name"),
                        rs.getString("username"), rs.getLong("generations"), rs.getLong("lines_added"),
                        rs.getLong("total_tokens"), instant(rs, "last_active_at")));
    }

    private List<FileTypeEfficiency> fileTypes(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        return jdbc.query(
                "SELECT fm.file_category, COALESCE(SUM(fm.lines_added),0) lines_added, " +
                        "COALESCE(SUM(fm.lines_deleted),0) lines_deleted, " +
                        "COALESCE(SUM(fm.files_created),0) files_created, COALESCE(SUM(fm.files_modified),0) files_modified " +
                        "FROM ai_generation_file_metric fm JOIN ai_generation_event e ON e.id=fm.generation_id " +
                        "JOIN iam_user u ON u.id=e.user_id " + parts.where() +
                        " GROUP BY fm.file_category ORDER BY lines_added DESC, fm.file_category LIMIT 30",
                parts.params(), (rs, row) -> new FileTypeEfficiency(rs.getString("file_category"),
                        rs.getLong("lines_added"), rs.getLong("lines_deleted"), rs.getLong("files_created"),
                        rs.getLong("files_modified")));
    }

    private TokenBreakdown tokenBreakdown(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        var row = jdbc.queryForMap(
                "SELECT " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.input_tokens END),0) input_tokens, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.output_tokens END),0) output_tokens, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.cache_read_tokens END),0) cache_read, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.cache_write_tokens END),0) cache_write, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.cache_miss_tokens END),0) cache_miss, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.thinking_tokens END),0) thinking " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where(), parts.params());
        long output = number(row.get("output_tokens"));
        long thinking = number(row.get("thinking"));
        return new TokenBreakdown(number(row.get("total_tokens")), number(row.get("input_tokens")), output,
                number(row.get("cache_read")), number(row.get("cache_write")), number(row.get("cache_miss")),
                thinking, Math.max(0, output - thinking));
    }

    private SkillSummary skillSummary(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail, String skillKey) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        MapSqlParameterSource params = parts.params();
        var row = jdbc.queryForMap(
                "SELECT COUNT(DISTINCT e.id) generations, COUNT(DISTINCT e.user_id) users, COUNT(DISTINCT e.project_key) projects, " +
                        "COALESCE(SUM(e.lines_added),0) lines_added, " +
                        "COALESCE(SUM(CASE WHEN e.token_quality IN " + COUNTABLE_QUALITY + " THEN e.total_tokens END),0) total_tokens " +
                        "FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where(), params);
        // 调用次数单独查：gs2 关联回满足条件的 Generation（同一套过滤，别名独立为 e2/u2）。
        QueryParts invParts = queryParts(actorId, globalAdmin, filters, detail);
        MapSqlParameterSource invParams = invParts.params();
        invParams.addValue("skillKey", skillKey);
        // where 里的 e./u. 别名替换为 e2./u2.，与子查询的表别名一致。
        String invWhere = invParts.where().replace("e.", "e2.").replace("u.", "u2.");
        Long invocations = jdbc.queryForObject(
                "SELECT COALESCE(SUM(gs2.invocation_count),0) FROM ai_generation_skill gs2 JOIN skill s3 ON s3.id=gs2.skill_id " +
                        "WHERE s3.skill_key=:skillKey AND gs2.generation_id IN " +
                        " (SELECT e2.id FROM ai_generation_event e2 JOIN iam_user u2 ON u2.id=e2.user_id " + invWhere + ")",
                invParams, Long.class);
        return new SkillSummary(invocations == null ? 0 : invocations, number(row.get("generations")), number(row.get("users")),
                number(row.get("projects")), number(row.get("lines_added")), number(row.get("total_tokens")));
    }

    private List<SkillCombo> skillCombos(Long actorId, boolean globalAdmin, Filters filters, String skillKey) {
        // 与目标 Skill 在同一 Generation 中共现的其它 Skill。
        QueryParts parts = queryParts(actorId, globalAdmin, filters, null);
        MapSqlParameterSource params = parts.params();
        params.addValue("skillKey", skillKey);
        return jdbc.query(
                "SELECT s.skill_key, MAX(s.display_name) display_name, COUNT(DISTINCT gs.generation_id) co_generations " +
                        "FROM ai_generation_skill gs JOIN skill s ON s.id=gs.skill_id " +
                        "WHERE s.skill_key <> :skillKey AND gs.generation_id IN (" +
                        "  SELECT gs2.generation_id FROM ai_generation_skill gs2 JOIN skill s2 ON s2.id=gs2.skill_id " +
                        "  JOIN ai_generation_event e ON e.id=gs2.generation_id JOIN iam_user u ON u.id=e.user_id " +
                        parts.where() + " AND s2.skill_key=:skillKey) " +
                        "GROUP BY s.id, s.skill_key ORDER BY co_generations DESC, s.skill_key LIMIT 20",
                params, (rs, row) -> new SkillCombo(rs.getString("skill_key"), rs.getString("display_name"),
                        rs.getLong("co_generations")));
    }

    private long scalarLong(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail, String expr) {
        QueryParts parts = queryParts(actorId, globalAdmin, filters, detail);
        Long value = jdbc.queryForObject(
                "SELECT " + expr + " FROM ai_generation_event e JOIN iam_user u ON u.id=e.user_id " + parts.where(),
                parts.params(), Long.class);
        return value == null ? 0 : value;
    }

    // ---- 过滤与团队 scope（与 SkillUsageAdminService 同口径）----

    private QueryParts queryParts(Long actorId, boolean globalAdmin, Filters filters, DetailFilter detail) {
        StringBuilder where = new StringBuilder(" WHERE e.started_at >= :from AND e.started_at < :to AND u.status='ACTIVE' ");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", filters.from()).addValue("to", filters.to());
        if (filters.userId() != null) {
            where.append(" AND e.user_id=:userId");
            params.addValue("userId", filters.userId());
        }
        List<Long> scopeTeamIds = new ArrayList<>();
        if (filters.teamId() != null) {
            if (!globalAdmin && !isManagedTeam(actorId, filters.teamId()))
                throw new AccessDeniedException("Team administrator permission required");
            if (!teamExists(filters.teamId())) throw error("TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND);
            scopeTeamIds.add(filters.teamId());
        } else if (!globalAdmin) {
            scopeTeamIds = assignments.findByUserId(actorId).stream()
                    .filter(a -> "TEAM_ADMIN".equals(a.getRoleKey()) && a.getTeam() != null)
                    .map(a -> a.getTeam().getId()).distinct().toList();
        }
        if (!globalAdmin || filters.teamId() != null) {
            if (scopeTeamIds.isEmpty()) {
                where.append(" AND 1=0");
            } else {
                where.append(" AND EXISTS (SELECT 1 FROM org_team_member scope_tm JOIN org_team scope_t ON scope_t.id=scope_tm.team_id " +
                        "WHERE scope_tm.user_id=e.user_id AND scope_tm.status='ACTIVE' AND scope_t.status='ACTIVE' " +
                        "AND scope_tm.team_id IN (:scopeTeamIds))");
                params.addValue("scopeTeamIds", scopeTeamIds);
            }
        }
        if (detail != null) {
            where.append(detail.condition());
            detail.binder().accept(params);
        }
        return new QueryParts(where.toString(), params);
    }

    private boolean isManagedTeam(Long actorId, Long teamId) {
        return assignments.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(actorId, "TEAM_ADMIN", "TEAM", teamId).isPresent();
    }

    private boolean teamExists(Long teamId) {
        return teams.findById(teamId).filter(team -> "ACTIVE".equals(team.getStatus())).isPresent();
    }

    private DimensionEfficiency dimensionEfficiency(ResultSet rs, int row) throws SQLException {
        return new DimensionEfficiency(rs.getString("dim_key"), rs.getString("dim_name"), rs.getLong("generations"),
                rs.getLong("users"), rs.getLong("lines_added"), rs.getLong("total_tokens"));
    }

    private GenerationRow generationRow(ResultSet rs, int row) throws SQLException {
        long totalTokens = rs.getLong("total_tokens");
        long linesAdded = rs.getLong("lines_added");
        return new GenerationRow(rs.getLong("id"), rs.getString("hook_generation_id"), instant(rs, "started_at"),
                rs.getLong("user_id"), rs.getString("display_name"), rs.getString("username"), rs.getString("team_names"),
                rs.getString("project_key"), rs.getString("project_name"), rs.getString("primary_stage"),
                rs.getString("status"), rs.getString("skill_keys"), linesAdded, rs.getLong("lines_deleted"),
                totalTokens, rs.getString("token_quality"),
                totalTokens == 0 ? 0 : (double) linesAdded / totalTokens * 1000,
                numberOrNull(rs.getObject("duration_ms")), numberOrNull(rs.getObject("model_call_count")),
                rs.getInt("tool_call_count"), rs.getInt("tool_failure_count"));
    }

    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static Long numberOrNull(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    private Filters effectiveFilters(Filters filters) {
        Instant now = clock.instant();
        return new Filters(filters.from() == null ? now.minus(Duration.ofDays(30)) : filters.from(),
                filters.to() == null ? now : filters.to(), filters.teamId(), filters.userId());
    }

    // ---- DTO ----

    public record Filters(Instant from, Instant to, Long teamId, Long userId) {}

    public record Summary(long generations, long activeUsers, long linesAdded, long linesDeleted,
            long inputTokens, long outputTokens, long totalTokens, double tokenCoverageRate,
            double locPer1kTokens, double avgGenerationDurationMs, double avgModelCalls, double toolFailureRate) {}

    public record TrendPoint(String bucket, long generations, long activeUsers, long linesAdded, long totalTokens) {}

    public record DimensionEfficiency(String key, String name, long generations, long users,
            long linesAdded, long totalTokens) {}

    public record SkillEfficiency(String skillKey, String displayName, long generations, long invocations,
            long users, long projects, Instant lastInvokedAt) {}

    public record MemberEfficiency(Long userId, String displayName, String username, long generations,
            long linesAdded, long totalTokens, Instant lastActiveAt) {}

    public record FileTypeEfficiency(String category, long linesAdded, long linesDeleted,
            long filesCreated, long filesModified) {}

    public record TokenBreakdown(long totalTokens, long inputTokens, long outputTokens, long cacheReadTokens,
            long cacheWriteTokens, long cacheMissTokens, long thinkingTokens, long answerTokens) {}

    public record SkillCombo(String skillKey, String displayName, long coGenerations) {}

    public record SkillSummary(long invocations, long generations, long users, long projects,
            long linesAdded, long totalTokens) {}

    public record Dashboard(Summary summary, List<TrendPoint> trend, List<DimensionEfficiency> stageRanking,
            List<DimensionEfficiency> teamRanking, List<DimensionEfficiency> projectRanking,
            List<SkillEfficiency> skillRanking, List<FileTypeEfficiency> fileTypes, TokenBreakdown tokenBreakdown,
            Instant from, Instant to) {}

    public record StageDetail(String stage, Summary summary, long projectCount, List<TrendPoint> trend,
            List<DimensionEfficiency> teamRanking, List<DimensionEfficiency> projectRanking,
            List<SkillEfficiency> skillRanking, List<FileTypeEfficiency> fileTypes, Instant from, Instant to) {}

    public record TeamDetail(Long teamId, String teamName, Summary summary, List<TrendPoint> trend,
            List<MemberEfficiency> members, List<DimensionEfficiency> stageDistribution,
            List<DimensionEfficiency> projectDistribution, List<SkillEfficiency> skillDistribution,
            Instant from, Instant to) {}

    public record ProjectDetail(String projectKey, String projectName, Summary summary, long skillCount,
            List<TrendPoint> trend, List<DimensionEfficiency> stageDistribution, List<FileTypeEfficiency> fileTypes,
            List<SkillEfficiency> skillRanking, List<MemberEfficiency> members, Instant from, Instant to) {}

    public record SkillDetail(String skillKey, SkillSummary summary, List<TrendPoint> trend,
            List<DimensionEfficiency> teamRanking, List<DimensionEfficiency> projectRanking,
            List<DimensionEfficiency> stageDistribution, List<FileTypeEfficiency> fileTypes,
            List<SkillCombo> skillCombos, Instant from, Instant to) {}

    public record GenerationPage(List<GenerationRow> items, int page, int size, long totalElements, int totalPages) {}

    public record GenerationRow(Long id, String generationId, Instant startedAt, Long userId, String displayName,
            String username, String teamNames, String projectKey, String projectName, String primaryStage,
            String status, String skillKeys, long linesAdded, long linesDeleted, long totalTokens,
            String tokenQuality, double locPer1kTokens, Long durationMs, Long modelCallCount,
            int toolCallCount, int toolFailureCount) {}

    private record QueryParts(String where, MapSqlParameterSource params) {}

    /** 详情页附加过滤条件：一段 SQL 片段 + 参数绑定器。 */
    private record DetailFilter(String condition, java.util.function.Consumer<MapSqlParameterSource> binder) {}
}
