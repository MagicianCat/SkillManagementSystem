package com.company.skillplatform.telemetry.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.user.application.ScopedRoleService;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.OrgTeamRepository;
import com.company.skillplatform.user.infrastructure.repository.ScopedRoleAssignmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillUsageAdminService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ScopedRoleService scopedRoles;
    private final ScopedRoleAssignmentRepository assignments;
    private final OrgTeamRepository teams;
    private final IamUserRepository users;
    private final SkillUsageConversationService conversations;
    private final AuditService audit;
    private final Clock clock = Clock.systemUTC();

    public SkillUsageAdminService(NamedParameterJdbcTemplate jdbc, ScopedRoleService scopedRoles,
            ScopedRoleAssignmentRepository assignments, OrgTeamRepository teams, IamUserRepository users,
            SkillUsageConversationService conversations, AuditService audit) {
        this.jdbc = jdbc;
        this.scopedRoles = scopedRoles;
        this.assignments = assignments;
        this.teams = teams;
        this.users = users;
        this.conversations = conversations;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AccessScope accessScope(Long actorId, boolean globalAdmin) {
        List<ScopedRoleService.TeamView> values = scopedRoles.tree(actorId, globalAdmin);
        return new AccessScope(globalAdmin, values.stream()
                .filter(team -> "ACTIVE".equals(team.status()))
                .map(team -> new TeamOption(team.id(), team.name(), team.parentId()))
                .toList());
    }

    @Transactional(readOnly = true)
    public Overview overview(Long actorId, boolean globalAdmin, Filters filters) {
        Filters effective = effectiveFilters(filters);
        QueryParts parts = queryParts(actorId, globalAdmin, effective);
        Map<String, Object> summary = jdbc.queryForMap(
                "SELECT COUNT(*) calls, COUNT(DISTINCT e.user_id) active_users, " +
                        "COUNT(DISTINCT e.skill_id) skills, " +
                        "SUM(CASE WHEN e.conversation_status = 'MERGED' THEN 1 ELSE 0 END) merged_conversations " +
                        "FROM skill_usage_event e JOIN iam_user u ON u.id=e.user_id JOIN skill s ON s.id=e.skill_id " +
                        parts.where(), parts.params());

        String format = Duration.between(effective.from(), effective.to()).toHours() <= 48
                ? "%Y-%m-%d %H:00:00" : "%Y-%m-%d";
        QueryParts trendParts = queryParts(actorId, globalAdmin, effective);
        List<TrendPoint> trend = jdbc.query(
                "SELECT DATE_FORMAT(e.invoked_at, '" + format + "') bucket, COUNT(*) calls, " +
                        "COUNT(DISTINCT e.user_id) active_users FROM skill_usage_event e " +
                        "JOIN iam_user u ON u.id=e.user_id JOIN skill s ON s.id=e.skill_id " +
                        trendParts.where() + " GROUP BY bucket ORDER BY bucket",
                trendParts.params(), (rs, row) -> new TrendPoint(rs.getString("bucket"), rs.getLong("calls"), rs.getLong("active_users")));

        QueryParts skillParts = queryParts(actorId, globalAdmin, effective);
        List<SkillRanking> skillRanking = jdbc.query(
                "SELECT e.skill_key_snapshot skill_key, MAX(s.display_name) display_name, COUNT(*) calls, " +
                        "COUNT(DISTINCT e.user_id) users, MAX(e.invoked_at) last_invoked_at " +
                        "FROM skill_usage_event e JOIN iam_user u ON u.id=e.user_id JOIN skill s ON s.id=e.skill_id " +
                        skillParts.where() + " GROUP BY e.skill_id, e.skill_key_snapshot ORDER BY calls DESC, skill_key LIMIT 100",
                skillParts.params(), (rs, row) -> new SkillRanking(rs.getString("skill_key"), rs.getString("display_name"),
                        rs.getLong("calls"), rs.getLong("users"), instant(rs, "last_invoked_at")));

        QueryParts memberParts = queryParts(actorId, globalAdmin, effective);
        List<MemberRanking> memberRanking = jdbc.query(
                "SELECT e.user_id, u.display_name, u.username, COUNT(*) calls, COUNT(DISTINCT e.skill_id) skills, " +
                        "MAX(e.invoked_at) last_invoked_at FROM skill_usage_event e JOIN iam_user u ON u.id=e.user_id " +
                        "JOIN skill s ON s.id=e.skill_id " + memberParts.where() +
                        " GROUP BY e.user_id, u.display_name, u.username ORDER BY calls DESC, display_name LIMIT 100",
                memberParts.params(), (rs, row) -> new MemberRanking(rs.getLong("user_id"), rs.getString("display_name"),
                        rs.getString("username"), rs.getLong("calls"), rs.getLong("skills"), instant(rs, "last_invoked_at")));

        QueryParts statusParts = queryParts(actorId, globalAdmin, effective);
        List<ConversationStatusCount> statuses = jdbc.query(
                "SELECT e.conversation_status status, COUNT(*) count FROM skill_usage_event e " +
                        "JOIN iam_user u ON u.id=e.user_id JOIN skill s ON s.id=e.skill_id " +
                        statusParts.where() + " GROUP BY e.conversation_status ORDER BY status",
                statusParts.params(), (rs, row) -> new ConversationStatusCount(rs.getString("status"), rs.getLong("count")));

        long calls = number(summary.get("calls"));
        long merged = number(summary.get("merged_conversations"));
        return new Overview(new Summary(calls, number(summary.get("active_users")), number(summary.get("skills")),
                merged, calls == 0 ? 0 : (double) merged / calls), trend, skillRanking, memberRanking, statuses,
                effective.from(), effective.to());
    }

    @Transactional(readOnly = true)
    public EventPage events(Long actorId, boolean globalAdmin, Filters filters, int page, int size) {
        filters = effectiveFilters(filters);
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.min(Math.max(size, 1), 100);
        QueryParts countParts = queryParts(actorId, globalAdmin, filters);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM skill_usage_event e JOIN iam_user u ON u.id=e.user_id " +
                "JOIN skill s ON s.id=e.skill_id " + countParts.where(), countParts.params(), Long.class);
        QueryParts rowsParts = queryParts(actorId, globalAdmin, filters);
        String sql = "SELECT e.event_uuid, e.invoked_at, e.user_id, u.display_name, u.username, " +
                "e.skill_key_snapshot, e.skill_version_id, COALESCE(sv.version, sv.candidate_version) version_label, " +
                "s.display_name skill_display_name, e.local_directory, e.client, e.client_version, e.agent_type, e.model, " +
                "e.client_session_id, e.conversation_status, COALESCE(c.message_count, 0) message_count, " +
                "CASE WHEN c.current_object_key IS NOT NULL AND c.status='ACTIVE' THEN 1 ELSE 0 END conversation_available, " +
                "(SELECT GROUP_CONCAT(DISTINCT t.team_name ORDER BY t.team_name SEPARATOR ', ') FROM org_team_member tm " +
                "JOIN org_team t ON t.id=tm.team_id WHERE tm.user_id=e.user_id AND tm.status='ACTIVE') team_names " +
                "FROM skill_usage_event e JOIN iam_user u ON u.id=e.user_id JOIN skill s ON s.id=e.skill_id " +
                "LEFT JOIN skill_version sv ON sv.id=e.skill_version_id " +
                "LEFT JOIN skill_usage_conversation c ON c.user_id=e.user_id AND c.client_session_id=e.client_session_id " +
                rowsParts.where() + " ORDER BY e.invoked_at DESC, e.id DESC LIMIT :limit OFFSET :offset";
        rowsParts.params().addValue("limit", boundedSize).addValue("offset", boundedPage * boundedSize);
        List<EventView> items = jdbc.query(sql, rowsParts.params(), this::eventView);
        int totalPages = total == 0 ? 0 : (int) ((total + boundedSize - 1) / boundedSize);
        return new EventPage(items, boundedPage, boundedSize, total, totalPages);
    }

    @Transactional
    public ConversationView conversation(Long actorId, boolean globalAdmin, String eventId, int page, int size) {
        Filters filters = new Filters(null, null, null, null, null);
        QueryParts parts = queryParts(actorId, globalAdmin, filters);
        MapSqlParameterSource params = parts.params();
        params.addValue("eventId", eventId);
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT e.id event_db_id, e.user_id, e.client_session_id FROM skill_usage_event e " +
                    "JOIN iam_user u ON u.id=e.user_id JOIN skill s ON s.id=e.skill_id " +
                    parts.where() + " AND e.event_uuid=:eventId", params);
        } catch (EmptyResultDataAccessException ex) {
            throw error("EVENT_NOT_FOUND", "Event not found", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        Long targetUserId = ((Number) row.get("user_id")).longValue();
        String sessionId = String.valueOf(row.get("client_session_id"));
        SkillUsageConversationService.ConversationPage result = conversations.readLatest(targetUserId, sessionId, page, size);
        IamUserEntity actor = users.findById(actorId).orElseThrow(() -> error("USER_NOT_FOUND", "User not found", org.springframework.http.HttpStatus.UNAUTHORIZED));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("eventUuid", eventId);
        metadata.put("targetUserId", targetUserId);
        metadata.put("clientSessionId", sessionId);
        metadata.put("status", result.status());
        audit.success("SKILL_USAGE_CONVERSATION_VIEWED", actor, "SKILL_USAGE_EVENT",
                ((Number) row.get("event_db_id")).longValue(), com.company.skillplatform.common.logging.LogContext.requestId(),
                Map.of(), Map.of(), metadata);
        return new ConversationView(result.status(), result.version(), result.totalMessages(), page, Math.min(Math.max(size, 1), 100), result.messages());
    }

    private EventView eventView(ResultSet rs, int row) throws SQLException {
        return new EventView(rs.getString("event_uuid"), instant(rs, "invoked_at"), rs.getLong("user_id"),
                rs.getString("display_name"), rs.getString("username"), rs.getString("skill_key_snapshot"),
                numberOrNull(rs.getObject("skill_version_id")), rs.getString("version_label"), rs.getString("skill_display_name"),
                rs.getString("local_directory"), rs.getString("team_names"), rs.getString("client"),
                rs.getString("client_version"), rs.getString("agent_type"), rs.getString("model"),
                rs.getString("client_session_id"), rs.getString("conversation_status"), rs.getInt("message_count"),
                rs.getInt("conversation_available") == 1);
    }

    private QueryParts queryParts(Long actorId, boolean globalAdmin, Filters filters) {
        Instant now = clock.instant();
        Instant from = filters.from() == null ? now.minus(Duration.ofDays(30)) : filters.from();
        Instant to = filters.to() == null ? now : filters.to();
        if (!from.isBefore(to)) throw error("INVALID_TIME_RANGE", "From must be before to", org.springframework.http.HttpStatus.BAD_REQUEST);
        StringBuilder where = new StringBuilder(" WHERE e.invoked_at >= :from AND e.invoked_at < :to AND u.status='ACTIVE' ");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        if (filters.skillKey() != null && !filters.skillKey().isBlank()) {
            where.append(" AND e.skill_key_snapshot=:skillKey");
            params.addValue("skillKey", filters.skillKey());
        }
        if (filters.userId() != null) {
            where.append(" AND e.user_id=:userId");
            params.addValue("userId", filters.userId());
        }
        List<Long> scopeTeamIds = new ArrayList<>();
        if (filters.teamId() != null) {
            if (!globalAdmin && !isManagedTeam(actorId, filters.teamId())) throw new AccessDeniedException("Team administrator permission required");
            if (!teamExists(filters.teamId())) throw error("TEAM_NOT_FOUND", "Team not found", org.springframework.http.HttpStatus.NOT_FOUND);
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
        return new QueryParts(where.toString(), params);
    }

    private boolean isManagedTeam(Long actorId, Long teamId) {
        return assignments.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(actorId, "TEAM_ADMIN", "TEAM", teamId).isPresent();
    }

    private boolean teamExists(Long teamId) {
        return teams.findById(teamId).filter(team -> "ACTIVE".equals(team.getStatus())).isPresent();
    }

    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static Long numberOrNull(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private BusinessException error(String code, String message, org.springframework.http.HttpStatus status) { return new BusinessException(code, message, status); }

    private Filters effectiveFilters(Filters filters) {
        Instant now = clock.instant();
        return new Filters(filters.from() == null ? now.minus(Duration.ofDays(30)) : filters.from(),
                filters.to() == null ? now : filters.to(), filters.teamId(), filters.skillKey(), filters.userId());
    }

    public record Filters(Instant from, Instant to, Long teamId, String skillKey, Long userId) {}
    public record AccessScope(boolean global, List<TeamOption> teams) {}
    public record TeamOption(Long id, String name, Long parentId) {}
    public record Summary(long calls, long activeUsers, long skills, long mergedConversations, double conversationSuccessRate) {}
    public record Overview(Summary summary, List<TrendPoint> trend, List<SkillRanking> skills,
                           List<MemberRanking> members, List<ConversationStatusCount> conversationStatuses,
                           Instant from, Instant to) {}
    public record TrendPoint(String bucket, long calls, long activeUsers) {}
    public record SkillRanking(String skillKey, String displayName, long calls, long users, Instant lastInvokedAt) {}
    public record MemberRanking(Long userId, String displayName, String username, long calls, long skills, Instant lastInvokedAt) {}
    public record ConversationStatusCount(String status, long count) {}
    public record EventPage(List<EventView> items, int page, int size, long totalElements, int totalPages) {}
    public record EventView(String eventId, Instant invokedAt, Long userId, String displayName, String username,
                            String skillKey, Long skillVersionId, String version, String skillDisplayName,
                            String localDirectory, String teamNames, String client, String clientVersion,
                            String agentType, String model, String clientSessionId, String conversationStatus,
                            int messageCount, boolean conversationAvailable) {}
    public record ConversationView(String status, long version, int totalMessages, int page, int size,
                                   Collection<com.fasterxml.jackson.databind.JsonNode> messages) {}
    private record QueryParts(String where, MapSqlParameterSource params) {}
}
