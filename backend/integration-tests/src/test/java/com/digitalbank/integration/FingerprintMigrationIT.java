package com.digitalbank.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * V1__scope_idempotency_fingerprints, run by the real AccountService against a
 * real Postgres.
 *
 * <p>The failure this guards against passes every other test. Hibernate's
 * ddl-auto: update never drops a constraint that is no longer declared, so the
 * entity changes alone give a fresh database the new per-account and
 * per-customer constraints while an existing database keeps the old global
 * ones as well, and the old ones go on winning. Unit tests cannot see that, and
 * neither can PaymentLifecycleIT, which always starts from an empty database.</p>
 *
 * <p>So the existing-database case starts from the schema the previous
 * AccountService actually generated: legacy-accountsdb-schema.sql is pg_dump
 * output, captured from the build before this change, not a hand-written
 * approximation of it. The constraint names in it are Hibernate's hashes,
 * which is exactly what the migration has to cope with.</p>
 *
 * <p>Each case boots the service jar as it ships, so Flyway and Hibernate run
 * in their real order under their real configuration. Only the datasource is
 * supplied directly instead of through Config Server; nothing else in
 * config-repo/account-service.yml bears on the schema.</p>
 */
class FingerprintMigrationIT {

    private static final DockerImageName JRE = DockerImageName.parse("eclipse-temurin:21-jre");
    private static final String DB = "accountsdb";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";
    private static final int PORT = 8084;
    private static final String JVM_OPTIONS =
            "-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Xmx256m";

    /** Postgres's code for a unique violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private Network network;
    private PostgreSQLContainer<?> postgres;
    private IdentityProviderStub identity;
    private GenericContainer<?> accountService;

    @BeforeAll
    static void utc() {
        // Postgres rejects the JVM's default zone name on this machine
        // (Asia/Calcutta); the service containers already run in UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @BeforeEach
    void startDatabase() throws Exception {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withDatabaseName(DB)
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases("postgres");
        postgres.start();

        identity = IdentityProviderStub.start("host.testcontainers.internal");
        Testcontainers.exposeHostPorts(identity.port());
    }

    @AfterEach
    void stopEverything() {
        if (accountService != null) {
            accountService.stop();
        }
        if (identity != null) {
            identity.close();
        }
        postgres.stop();
        network.close();
    }

    // ------------------------------------------------------------ the cases

    @Test
    @DisplayName("an existing database loses its global uniqueness and gains the scoped constraints")
    void existingDatabaseIsMigrated() throws Exception {
        loadLegacySchema();

        // The starting point, asserted rather than assumed: the captured schema
        // really does carry global uniqueness on both columns.
        assertThat(uniqueColumnSets("account_hold")).contains("request_fingerprint");
        assertThat(uniqueColumnSets("account")).contains("request_fingerprint");

        UUID customerOnesAccount = insertAccount("cust-1", "a1b2c3d4");
        UUID customerTwosAccount = insertAccount("cust-2", "client-key-1");
        insertHold(customerOnesAccount, "hold-key-1");

        startAccountService();

        assertScopedShape();

        // Baselined at 0, then V1 ran: the default baseline of 1 would have
        // marked V1 applied and skipped it on exactly this kind of database.
        assertThat(migrationHistory()).containsExactly("0:BASELINE:true", "1:SQL:true");

        // Existing rows are untouched, fingerprints included: nothing is
        // backfilled.
        assertThat(fingerprintOf("account", customerOnesAccount)).isEqualTo("a1b2c3d4");
        assertThat(fingerprintOf("account", customerTwosAccount)).isEqualTo("client-key-1");

        assertScopedBehaviour(customerOnesAccount, customerTwosAccount, "hold-key-1", "a1b2c3d4");
    }

    @Test
    @DisplayName("a migrated database is left alone on the next start")
    void restartIsANoOp() throws Exception {
        loadLegacySchema();
        UUID account = insertAccount("cust-1", "a1b2c3d4");
        insertHold(account, "hold-key-1");

        startAccountService();
        accountService.stop();
        startAccountService();

        // V1 is not run twice, and Hibernate's own pass on the second start
        // leaves the constraints as they were.
        assertThat(migrationHistory()).containsExactly("0:BASELINE:true", "1:SQL:true");
        assertScopedShape();
    }

    @Test
    @DisplayName("a fresh database ends up with exactly the same constraints")
    void freshDatabaseGetsTheSameShape() throws Exception {
        // Flyway runs first and finds no tables, so V1 must do nothing; the
        // constraints then come from the entities, via Hibernate.
        startAccountService();

        assertThat(migrationHistory()).containsExactly("1:SQL:true");
        assertScopedShape();

        UUID customerOnesAccount = insertAccount("cust-1", "a1b2c3d4");
        UUID customerTwosAccount = insertAccount("cust-2", "client-key-1");
        insertHold(customerOnesAccount, "hold-key-1");

        assertScopedBehaviour(customerOnesAccount, customerTwosAccount, "hold-key-1", "a1b2c3d4");
    }

    // ------------------------------------------------------- the assertions

    /**
     * The only uniqueness covering request_fingerprint is the scoped pair, on
     * both tables. Asserted as the whole set of unique column lists that
     * mention the column, so a leftover global index fails it as surely as a
     * missing scoped one.
     */
    private void assertScopedShape() throws SQLException {
        assertThat(uniqueColumnSets("account_hold"))
                .filteredOn(columns -> columns.contains("request_fingerprint"))
                .containsExactly("account_id,request_fingerprint");

        assertThat(uniqueColumnSets("account"))
                .filteredOn(columns -> columns.contains("request_fingerprint"))
                .containsExactly("customer_id,request_fingerprint");
    }

    /**
     * What the constraints are for, tried against the database itself: a key
     * is reusable across owners and still refused within one.
     */
    private void assertScopedBehaviour(UUID customerOnesAccount, UUID customerTwosAccount,
                                       String holdKey, String createFingerprint) throws SQLException {

        // Another account may use a hold key this account already used. Under
        // the old global constraint this failed.
        insertHold(customerTwosAccount, holdKey);

        // The same account may not.
        assertUniqueViolation(() -> insertHold(customerOnesAccount, holdKey));

        // Another customer may create under a fingerprint this customer used.
        insertAccount("cust-2", createFingerprint);

        // The same customer may not.
        assertUniqueViolation(() -> insertAccount("cust-1", createFingerprint));

        // Keyless rows are unconstrained, as before: NULLs are distinct.
        insertHold(customerOnesAccount, null);
        insertHold(customerOnesAccount, null);
    }

    private static void assertUniqueViolation(SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(UNIQUE_VIOLATION);
    }

    // ------------------------------------------------------------ the setup

    private void loadLegacySchema() throws Exception {
        Path schema = Path.of("src/test/resources/migration/legacy-accountsdb-schema.sql");

        // The file is pg_dump's output as captured, so it carries psql's own
        // backslash directives (restrict and unrestrict). Those are client
        // commands rather than SQL and mean nothing over JDBC, so they are
        // skipped here instead of being edited out of the capture.
        String sql;
        try (var lines = Files.lines(schema, StandardCharsets.UTF_8)) {
            sql = lines.filter(line -> !line.startsWith("\\")).collect(java.util.stream.Collectors.joining("\n"));
        }

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private void startAccountService() {
        Path jar = repoPath("backend/AccountService-develop/target/AccountService-0.0.1-SNAPSHOT.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "missing " + jar + "; build the services first, e.g. mvn -DskipTests install");
        }

        accountService = new GenericContainer<>(JRE)
                .withNetwork(network)
                .withExposedPorts(PORT)
                .withCopyFileToContainer(MountableFile.forHostPath(jar), "/app/app.jar")
                .withCommand("java", "-jar", "/app/app.jar")
                .withEnv("JAVA_TOOL_OPTIONS", JVM_OPTIONS)
                // No Config Server here: the import is optional, so pointing it
                // at nothing lets the service start on its own configuration.
                .withEnv("CONFIG_SERVER_URL", "http://127.0.0.1:1")
                .withEnv("SPRING_CLOUD_CONFIG_FAIL_FAST", "false")
                .withEnv("EUREKA_CLIENT_ENABLED", "false")
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/" + DB)
                .withEnv("SPRING_DATASOURCE_USERNAME", DB_USER)
                .withEnv("SPRING_DATASOURCE_PASSWORD", DB_PASSWORD)
                .withEnv("AUTH0_ISSUER_URI", identity.issuer())
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forPort(PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(4)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("container.AccountService")));
        accountService.start();
    }

    // ------------------------------------------------------------- the SQL

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + DB,
                DB_USER, DB_PASSWORD);
    }

    /** Every unique index on the table, as its columns in order, comma-joined. */
    private List<String> uniqueColumnSets(String table) throws SQLException {
        String sql = """
                SELECT string_agg(att.attname, ',' ORDER BY key.ord)
                FROM pg_index ix
                JOIN pg_class tbl ON tbl.oid = ix.indrelid
                CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS key(attnum, ord)
                JOIN pg_attribute att ON att.attrelid = tbl.oid AND att.attnum = key.attnum
                WHERE tbl.relname = ? AND ix.indisunique
                GROUP BY ix.indexrelid
                """;
        List<String> sets = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sets.add(rs.getString(1));
                }
            }
        }
        return sets;
    }

    /** Flyway's history, as version:type:success, oldest first. */
    private List<String> migrationHistory() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version, type, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getBoolean(3));
            }
        }
        return rows;
    }

    private String fingerprintOf(String table, UUID id) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT request_fingerprint FROM " + table + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private UUID insertAccount(String customerId, String fingerprint) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO account (id, customer_id, account_number, account_type, account_sub_type,
                                          status, currency, balance, version, request_fingerprint)
                     VALUES (?, ?, ?, 'CHEQUING', 'PERSONAL', 'ACTIVE', 'CAD', 0, 0, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setString(2, customerId);
            ps.setString(3, "9" + Math.abs(id.getMostSignificantBits() % 1_000_000_000L));
            ps.setString(4, fingerprint);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertHold(UUID accountId, String fingerprint) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO account_hold (id, account_id, amount, status, request_fingerprint)
                     VALUES (?, ?, 25.00, 'ACTIVE', ?)
                     """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, accountId);
            ps.setString(3, fingerprint);
            ps.executeUpdate();
        }
    }

    private static Path repoPath(String relative) {
        return Path.of("..", "..").resolve(relative).toAbsolutePath().normalize();
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
