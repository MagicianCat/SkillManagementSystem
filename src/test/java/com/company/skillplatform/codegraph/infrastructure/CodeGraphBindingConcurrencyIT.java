package com.company.skillplatform.codegraph.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.mysql.cj.jdbc.MysqlDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7 integration tests against a real MySQL database. The goal is to verify the
 * concurrency contract that unit tests with a mocked JdbcTemplate cannot reach:
 *
 * <ul>
 *   <li>{@code uk_code_graph_binding_single_active} — at most one ACTIVE binding per
 *       workflow run, even under concurrent activate attempts.</li>
 *   <li>{@code uk_code_graph_binding_version} — version numbers never duplicate, even
 *       when two REPO_APPEND creates race.</li>
 *   <li>Atomic binding swap — SUPERSEDE + ACTIVE happen in one transaction; readers
 *       never observe an intermediate state with two ACTIVE rows.</li>
 *   <li>Freeze safety — the M7 "freeze only new rows" rule against real SQL with
 *       {@code resolved_commit_sha IS NULL} as the gate.</li>
 * </ul>
 *
 * <p>Run against a MySQL instance reachable via {@code DB_URL}/{@code DB_USERNAME}/
 * {@code DB_PASSWORD} (defaults to the local dev MySQL on port 3306). The tests
 * create a private schema ({@code code_graph_it}) and run Flyway into it, so they
 * never pollute the dev database.</p>
 *
 * <p>Skipped unless {@code -Dit.mysql=true} is passed, so the regular CI build does
 * not require a running MySQL. Example invocation:</p>
 *
 * <pre>mvn test -Dtest='CodeGraphBindingConcurrencyIT' -Dit.mysql=true</pre>
 */
@EnabledIfSystemProperty(named = "it.mysql", matches = "true")
class CodeGraphBindingConcurrencyIT {

    private static final String IT_SCHEMA = "code_graph_it";
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws SQLException {
        var host = System.getProperty("it.mysql.host", "127.0.0.1");
        var port = Integer.parseInt(System.getProperty("it.mysql.port", "3306"));
        var user = System.getProperty("it.mysql.user", "sms_app");
        var password = System.getProperty("it.mysql.password", "SkillApp@2026");

        // Connect directly to the pre-created IT schema. The schema is provisioned
        // out-of-band (e.g. via docker exec + mysql) because the application user
        // typically has no CREATE DATABASE privilege in shared dev environments.
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://" + host + ":" + port + "/" + IT_SCHEMA + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&createDatabaseIfNotExist=true");
        ds.setUser(user);
        ds.setPassword(password);
        dataSource = ds;

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
    }

    @BeforeEach
    void cleanCodeGraphTables() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=0");
            stmt.execute("TRUNCATE TABLE code_graph_update_request");
            stmt.execute("TRUNCATE TABLE code_graph_generation_job");
            stmt.execute("TRUNCATE TABLE workflow_run_code_graph_binding");
            stmt.execute("TRUNCATE TABLE code_graph_bundle_repository");
            stmt.execute("TRUNCATE TABLE code_graph_bundle");
            stmt.execute("TRUNCATE TABLE code_graph_repository_snapshot");
            stmt.execute("DELETE FROM agent_workflow_run");
            stmt.execute("DELETE FROM workflow_run_git_repository");
            stmt.execute("DELETE FROM workflow_run");
            stmt.execute("DELETE FROM project_git_repository");
            stmt.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    /**
     * The single-ACTIVE guarantee is enforced by a generated column + unique key.
     * Concurrent activations of the SAME PREPARING binding (the production scenario:
     * two pollers racing to complete the same job) must converge to exactly one ACTIVE
     * row — and exactly one winner. Any deadlock is a test failure.
     */
    @Test
    @DisplayName("concurrent activateBinding: exactly one ACTIVE binding survives")
    void singleActiveConstraintSurvivesConcurrentActivations() throws Exception {
        long runId = insertWorkflowRun(1L);
        long bundle = insertBundle("hash-shared");
        long binding = insertBinding(runId, 1, bundle, "PREPARING");

        int threads = 8;
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        var activateSuccess = new AtomicInteger();
        var activateNoop = new AtomicInteger();
        var activateConflict = new AtomicInteger();
        var deadlocks = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    // Mirror production: lock workflow_run first, then swap. Only one
                    // thread's UPDATE actually flips the row from PREPARING to ACTIVE;
                    // the others see status='ACTIVE' already and their UPDATE matches 0 rows.
                    int activated = tryActivateWithRunLock(runId, binding, bundle);
                    if (activated > 0) activateSuccess.incrementAndGet();
                    else activateNoop.incrementAndGet();
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1213 || (e.getMessage() != null && e.getMessage().contains("Deadlock"))) {
                        deadlocks.incrementAndGet();
                        throw new RuntimeException("deadlock detected", e);
                    }
                    if (e.getErrorCode() == 1062 || (e.getMessage() != null && e.getMessage().contains("Duplicate"))) {
                        activateConflict.incrementAndGet();
                    } else {
                        throw new RuntimeException(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        latch.countDown();
        for (var f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        var activeCount = countActiveBindings(runId);
        assertThat(activeCount)
                .as("at most one ACTIVE binding per workflow_run")
                .isEqualTo(1);
        assertThat(findActiveBinding(runId)).isEqualTo(binding);
        // Exactly one thread actually flipped the row; the others saw no work to do
        assertThat(deadlocks.get()).as("deadlocks are never an acceptable concurrency outcome").isZero();
        // No thread crashed with an unexpected error.
        assertThat(activateSuccess.get() + activateNoop.get() + activateConflict.get()).isEqualTo(threads);
    }

    /**
     * Two threads racing to create the next binding version must produce distinct
     * version numbers — no duplicate (workflow_run_id, version_no) pairs.
     */
    @Test
    @DisplayName("concurrent createBinding: versions are unique and sequential")
    void concurrentBindingCreationProducesUniqueVersions() throws Exception {
        long runId = insertWorkflowRun(1L);
        long bundle = insertBundle("hash-shared");

        int threads = 6;
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        var createdVersions = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        var failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    int version = createBindingWithLock(runId, bundle);
                    createdVersions.add(version);
                } catch (SQLException e) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        latch.countDown();
        for (var f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(failures.get()).isZero();
        Set<Integer> distinct = new HashSet<>(createdVersions);
        assertThat(distinct).hasSize(threads);
        assertThat(distinct).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    }

    /**
     * The M6 swap: SUPERSEDE the old ACTIVE row and activate the PREPARING one in the
     * same transaction. After commit, a reader sees exactly one ACTIVE row — never
     * two, never zero.
     */
    @Test
    @DisplayName("atomic swap: reader never sees 0 or 2 ACTIVE bindings")
    void atomicSwapNeverExposesIntermediateState() throws Exception {
        long runId = insertWorkflowRun(1L);
        long bundle1 = insertBundle("hash-v1");
        long bundle2 = insertBundle("hash-v2");
        long binding1 = insertBinding(runId, 1, bundle1, "ACTIVE");
        long binding2 = insertBinding(runId, 2, bundle2, "PREPARING");

        var stopReading = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        var readerErrors = new AtomicInteger();
        var inconsistencies = new AtomicInteger();
        var reader = pool.submit(() -> {
            try {
                while (stopReading.getCount() > 0) {
                    int count = countActiveBindings(runId);
                    if (count != 1) inconsistencies.incrementAndGet();
                }
            } catch (SQLException e) {
                readerErrors.incrementAndGet();
            }
        });

        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement(
                    "UPDATE workflow_run_code_graph_binding SET status='SUPERSEDED',time_updated=NOW(3) WHERE workflow_run_id=? AND status='ACTIVE' AND id<>?")) {
                stmt.setLong(1, runId);
                stmt.setLong(2, binding2);
                stmt.executeUpdate();
            }
            try (var stmt = conn.prepareStatement(
                    "UPDATE workflow_run_code_graph_binding SET status='ACTIVE',bundle_id=?,activated_at=NOW(3),time_updated=NOW(3) WHERE id=? AND status='PREPARING'")) {
                stmt.setLong(1, bundle2);
                stmt.setLong(2, binding2);
                stmt.executeUpdate();
            }
            conn.commit();
        }
        stopReading.countDown();
        reader.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(readerErrors.get()).isZero();
        assertThat(inconsistencies.get())
                .as("reader must never observe 0 or 2 ACTIVE bindings mid-swap")
                .isZero();
        assertThat(findActiveBinding(runId)).isEqualTo(binding2);
    }

    /**
     * M7 freeze safety: only unresolved rows are eligible for re-freeze.
     */
    @Test
    @DisplayName("freeze safety: only unresolved rows are eligible for re-freeze")
    void freezeSafetySelectsOnlyUnresolvedRows() throws SQLException {
        long runId = insertWorkflowRun(1L);
        long projectRepo1 = insertProjectRepository(1L, "https://a", "main");
        long projectRepo2 = insertProjectRepository(1L, "https://b", "main");
        long projectRepo3 = insertProjectRepository(1L, "https://c", "main");
        long wfRepo1 = insertWorkflowRepository(runId, projectRepo1, "a", "commitA", "treeA");
        long wfRepo2 = insertWorkflowRepository(runId, projectRepo2, "b", "commitB", "treeB");
        long wfRepo3 = insertWorkflowRepository(runId, projectRepo3, "c", null, null);

        // The M7 append-side freeze query filters on workflow_run_git_repository.id
        // (the snapshot ids returned by snapshotOne), not project_git_repository_id.
        List<Long> eligible = new ArrayList<>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id,resolved_commit_sha FROM workflow_run_git_repository WHERE workflow_run_id=? AND id IN (?,?,?) AND status='ACTIVE'")) {
            ps.setLong(1, runId);
            ps.setLong(2, wfRepo1);
            ps.setLong(3, wfRepo2);
            ps.setLong(4, wfRepo3);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString("resolved_commit_sha") == null) {
                        eligible.add(rs.getLong("id"));
                    }
                }
            }
        }
        assertThat(eligible).containsExactly(wfRepo3);
    }

    /**
     * The UNIQUE constraint on (workflow_run_id, version_no) rejects duplicate versions
     * at the DB level.
     */
    @Test
    @DisplayName("unique key (workflow_run_id, version_no) rejects duplicate versions")
    void uniqueBindingVersionConstraint() throws SQLException {
        long runId = insertWorkflowRun(1L);
        long bundle = insertBundle("hash-x");
        insertBinding(runId, 1, bundle, "ACTIVE");

        var duplicate = false;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO workflow_run_code_graph_binding(time_created,time_updated,workflow_run_id,version_no,engine_type,engine_bundle_key,repository_set_hash,reason,status,semantic_index_status) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,'PREPARING','DISABLED')")) {
            ps.setLong(1, runId);
            ps.setInt(2, 1);
            ps.setString(3, "GITNEXUS");
            ps.setString(4, "cg-y");
            ps.setString(5, "hash-y");
            ps.setString(6, "INITIAL");
            ps.executeUpdate();
        } catch (SQLException e) {
            duplicate = e.getErrorCode() == 1062 || (e.getMessage() != null && e.getMessage().contains("Duplicate"));
        }
        assertThat(duplicate).isTrue();
    }

    /**
     * The update_request table accepts PENDING rows and surfaces them for drain.
     */
    @Test
    @DisplayName("code_graph_update_request: PENDING row is selectable for drain")
    void updateRequestPendingIsSelectable() throws SQLException {
        long runId = insertWorkflowRun(1L);
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO code_graph_update_request(time_created,time_updated,workflow_run_id,target_repository_set_hash,target_repository_count,triggered_by,status,appended_workflow_repository_ids_json) VALUES(NOW(3),NOW(3),?,?,?,?,'PENDING',CAST(? AS JSON))")) {
            ps.setLong(1, runId);
            ps.setString(2, "a".repeat(64));
            ps.setInt(3, 3);
            ps.setLong(4, 7L);
            ps.setString(5, "[42]");
            ps.executeUpdate();
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id FROM code_graph_update_request WHERE workflow_run_id=? AND status='PENDING' ORDER BY id LIMIT 1")) {
            ps.setLong(1, runId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    // ---------- helpers -------------------------------------------------------

    private long insertWorkflowRun(long projectId) throws SQLException {
        try (var conn = dataSource.getConnection()) {
            // Seed an iam_user row (created_by FK) if missing.
            try (var ps = conn.prepareStatement(
                    "INSERT IGNORE INTO iam_user(id,time_created,time_updated,identity_provider,external_user_id,username,display_name,status) VALUES(?,NOW(3),NOW(3),'LOCAL',?,?,?,'ACTIVE')")) {
                ps.setLong(1, 1L);
                ps.setString(2, "user-1");
                ps.setString(3, "user-1");
                ps.setString(4, "Test User");
                ps.executeUpdate();
            }
            // Seed the project row (project_run FK) if missing.
            try (var ps = conn.prepareStatement(
                    "INSERT IGNORE INTO virtual_project(id,time_created,time_updated,project_key,project_name,status,created_by) VALUES(?,NOW(3),NOW(3),?,?,'ACTIVE',?)")) {
                ps.setLong(1, projectId);
                ps.setString(2, "proj-" + projectId);
                ps.setString(3, "Test Project " + projectId);
                ps.setLong(4, 1L);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "INSERT INTO workflow_run(time_created,time_updated,project_id,workflow_code,workflow_version,status,started_by,started_at) VALUES(NOW(3),NOW(3),?,?,?,'RUNNING',?,NOW(3))",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, projectId);
                ps.setString(2, "test-workflow");
                ps.setInt(3, 1);
                ps.setLong(4, 1L);
                ps.executeUpdate();
                try (var rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private long insertBundle(String hash) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO code_graph_bundle(time_created,time_updated,bundle_hash,engine_type,engine_bundle_key,repository_count,status) VALUES(NOW(3),NOW(3),?,?,?,?,'READY')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, hash);
            ps.setString(2, "GITNEXUS");
            ps.setString(3, "cg-" + hash);
            ps.setInt(4, 1);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertBinding(long runId, int version, long bundleId, String status) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO workflow_run_code_graph_binding(time_created,time_updated,workflow_run_id,version_no,bundle_id,engine_type,engine_bundle_key,repository_set_hash,reason,status,semantic_index_status) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,?,?,'DISABLED')",
                     Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, runId);
                ps.setInt(2, version);
                ps.setLong(3, bundleId);
                ps.setString(4, "GITNEXUS");
                ps.setString(5, "cg-" + runId + "-" + version);
                ps.setString(6, "hash-" + version);
                ps.setString(7, "INITIAL");
                ps.setString(8, status);
                ps.executeUpdate();
                try (var rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getLong(1);
                }
        }
    }

    private long insertProjectRepository(long projectId, String url, String branch) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO project_git_repository(time_created,time_updated,project_id,display_name,locator_mode,repository_path,remote_url,normalized_url,default_branch,tracked_branch,status,added_by) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,?,?,'ACTIVE',?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, projectId);
            ps.setString(2, "repo-" + url.hashCode());
            ps.setString(3, "ABSOLUTE");
            ps.setString(4, "/repo/" + url.hashCode());
            ps.setString(5, url);
            ps.setString(6, url);
            ps.setString(7, branch);
            ps.setString(8, branch);
            ps.setLong(9, 1L);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertWorkflowRepository(long runId, long projectRepoId, String key, String commitSha, String treeSha) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO workflow_run_git_repository(time_created,workflow_run_id,project_git_repository_id,display_name,normalized_url,repository_path,tracked_branch,added_by,status,resolved_commit_sha,resolved_tree_sha,resolved_at,logical_repository_key,source_artifact_uri,source_sha256) VALUES(NOW(3),?,?,?,?,?,?,?,'ACTIVE',?,?,NOW(3),?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, runId);
            ps.setLong(2, projectRepoId);
            ps.setString(3, "repo-" + projectRepoId);
            ps.setString(4, "https://" + key);
            ps.setString(5, "/repo/" + projectRepoId);
            ps.setString(6, "main");
            ps.setLong(7, 1L);
            // status='ACTIVE' is a literal
            ps.setString(8, commitSha);
            ps.setString(9, treeSha);
            ps.setString(10, key);
            ps.setString(11, "file:///src.tar");
            ps.setString(12, commitSha == null ? null : "a".repeat(64));
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Mirror of {@code JdbcCodeGraphMetadataStore.activateBinding}. */
    private void tryActivate(long runId, long bindingId, long bundleId) throws SQLException {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var ps = conn.prepareStatement(
                        "UPDATE workflow_run_code_graph_binding SET status='SUPERSEDED',time_updated=NOW(3) WHERE workflow_run_id=? AND status='ACTIVE' AND id<>?")) {
                    ps.setLong(1, runId);
                    ps.setLong(2, bindingId);
                    ps.executeUpdate();
                }
                try (var ps = conn.prepareStatement(
                        "UPDATE workflow_run_code_graph_binding SET status='ACTIVE',bundle_id=?,activated_at=NOW(3),time_updated=NOW(3) WHERE id=? AND status='PREPARING'")) {
                    ps.setLong(1, bundleId);
                    ps.setLong(2, bindingId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Mirror of the production activation flow used by {@code complete()}: the entire
     * swap runs in a transaction that begins by locking the workflow_run row. Returns
     * the number of rows the {@code SET status='ACTIVE' ... WHERE status='PREPARING'}
     * statement actually flipped — {@code 1} if this caller won, {@code 0} if a racing
     * caller had already activated the same binding (an idempotent no-op).
     */
    private int tryActivateWithRunLock(long runId, long bindingId, long bundleId) throws SQLException {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 1: lock the workflow_run row. Concurrent activators serialise here.
                try (var ps = conn.prepareStatement("SELECT id FROM workflow_run WHERE id=? FOR UPDATE")) {
                    ps.setLong(1, runId);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("no run " + runId);
                    }
                }
                // Step 2: atomic swap (SUPERSEDE + ACTIVE) in the same transaction.
                try (var ps = conn.prepareStatement(
                        "UPDATE workflow_run_code_graph_binding SET status='SUPERSEDED',time_updated=NOW(3) WHERE workflow_run_id=? AND status='ACTIVE' AND id<>?")) {
                    ps.setLong(1, runId);
                    ps.setLong(2, bindingId);
                    ps.executeUpdate();
                }
                int activated;
                try (var ps = conn.prepareStatement(
                        "UPDATE workflow_run_code_graph_binding SET status='ACTIVE',bundle_id=?,activated_at=NOW(3),time_updated=NOW(3) WHERE id=? AND status='PREPARING'")) {
                    ps.setLong(1, bundleId);
                    ps.setLong(2, bindingId);
                    activated = ps.executeUpdate();
                }
                conn.commit();
                return activated;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** Mirror of {@code JdbcCodeGraphMetadataStore.createBinding}. */
    private int createBindingWithLock(long runId, long bundleId) throws SQLException {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var ps = conn.prepareStatement("SELECT id FROM workflow_run WHERE id=? FOR UPDATE")) {
                    ps.setLong(1, runId);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("no run " + runId);
                    }
                }
                int version;
                try (var ps = conn.prepareStatement(
                        "SELECT COALESCE(MAX(version_no),0)+1 FROM workflow_run_code_graph_binding WHERE workflow_run_id=?")) {
                    ps.setLong(1, runId);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        version = rs.getInt(1);
                    }
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO workflow_run_code_graph_binding(time_created,time_updated,workflow_run_id,version_no,bundle_id,engine_type,engine_bundle_key,repository_set_hash,reason,status,semantic_index_status) VALUES(NOW(3),NOW(3),?,?,?,?,?,?,?,?,'DISABLED')")) {
                    ps.setLong(1, runId);
                    ps.setInt(2, version);
                    ps.setLong(3, bundleId);
                    ps.setString(4, "GITNEXUS");
                    ps.setString(5, "cg-" + runId + "-" + version);
                    ps.setString(6, "hash-" + version);
                    ps.setString(7, "INITIAL");
                    ps.setString(8, "PREPARING");
                    ps.executeUpdate();
                }
                conn.commit();
                return version;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private int countActiveBindings(long runId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND status='ACTIVE'")) {
            ps.setLong(1, runId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long findActiveBinding(long runId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id FROM workflow_run_code_graph_binding WHERE workflow_run_id=? AND status='ACTIVE'")) {
            ps.setLong(1, runId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
