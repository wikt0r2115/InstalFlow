package pl.instalflow.identity.persistence;

import pl.instalflow.InstalFlowApplication;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pl.instalflow.identity.domain.AppUser;
import pl.instalflow.identity.domain.RoleEnum;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class AppUserMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradesAppliedV1WithoutLosingUsersAndEnforcesAccountConstraints() throws SQLException {
        String schema = "app_user_upgrade";
        migrate(schema, "1");

        UUID oldId = UUID.randomUUID();
        execute("INSERT INTO " + schema + ".app_user "
                + "(id, user_number, email_normalized, password_hash, role, active, version, created_at) "
                + "VALUES ('" + oldId + "', 7, ' \tLegacy@Example.com\t ', 'old-hash', 'TECHNICIAN', false, 3, "
                + "TIMESTAMP '2026-01-02 03:04:05')");

        migrate(schema, null);

        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version = '1' AND success"));
        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version = '2' AND success"));
        assertEquals(1, count("SELECT count(*) FROM " + schema + ".app_user WHERE id = '" + oldId
                + "' AND user_number = 7 AND email_normalized = 'legacy@example.com' "
                + "AND active = false AND version = 3 AND "
                + "created_at AT TIME ZONE 'UTC' = TIMESTAMP '2026-01-02 03:04:05'"));

        long firstNumber = insertAccount(schema, "one@example.com", "OWNER");
        long secondNumber = insertAccount(schema, "two@example.com", "OFFICE");
        assertEquals(8, firstNumber);
        assertNotEquals(firstNumber, secondNumber);
        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".app_user WHERE email_normalized = 'one@example.com' AND active AND version = 0 "
                + "AND created_at IS NOT NULL AND pg_typeof(created_at) = 'timestamp with time zone'::regtype"));

        execute("UPDATE " + schema + ".app_user SET active = false WHERE email_normalized = 'one@example.com'");
        assertSqlState("23505", () -> insertAccount(schema, "one@example.com", "OWNER"));
        assertSqlState("23514", () -> insertAccount(schema, "bad-role@example.com", "ADMIN"));
        assertSqlState("23514", () -> execute("UPDATE " + schema
                + ".app_user SET version = -1 WHERE email_normalized = 'two@example.com'"));
    }

    @Test
    void createsCompleteSchemaOnFreshDatabase() throws SQLException, ReflectiveOperationException {
        String schema = "app_user_fresh";
        migrate(schema, null);

        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version = '1' AND success"));
        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version = '2' AND success"));
        assertEquals(1, insertAccount(schema, "fresh@example.com", "TECHNICIAN"));

        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(InstalFlowApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl() + separator + "currentSchema=" + schema,
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.flyway.locations=classpath:db/migration",
                        "--spring.flyway.schemas=" + schema,
                        "--spring.flyway.default-schema=" + schema,
                        "--spring.jpa.hibernate.ddl-auto=validate")) {
            assertTrue(ignored.isActive());

            var constructor = AppUser.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            AppUser user = constructor.newInstance();
            ReflectionTestUtils.setField(user, "emailNormalized", "jpa@example.com");
            ReflectionTestUtils.setField(user, "passwordHash", "test-hash");
            ReflectionTestUtils.setField(user, "role", RoleEnum.TECHNICIAN);

            try (EntityManager entityManager = ignored.getBean(EntityManagerFactory.class).createEntityManager()) {
                entityManager.getTransaction().begin();
                entityManager.persist(user);
                entityManager.flush();
                assertTrue(ReflectionTestUtils.getField(user, "id") instanceof UUID);
                assertEquals(2L, ReflectionTestUtils.getField(user, "userNumber"));
                assertTrue(ReflectionTestUtils.getField(user, "createdAt") instanceof java.time.Instant);
                entityManager.getTransaction().commit();
            }
            assertEquals(1, count("SELECT count(*) FROM " + schema
                    + ".app_user WHERE email_normalized = 'jpa@example.com' AND user_number = 2"));
        }
    }

    @Test
    void refusesUpgradeWhenLegacyEmailsCollideAfterNormalization() throws SQLException {
        String schema = "app_user_conflicting_emails";
        migrate(schema, "1");

        insertLegacyAccount(schema, 1, "Case@Example.com");
        insertLegacyAccount(schema, 2, "\tcase@example.com\t");

        assertThrows(FlywayException.class, () -> migrate(schema, null));
        assertEquals(2, count("SELECT count(*) FROM " + schema + ".app_user"));
        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version = '1' AND success"));
    }

    @Test
    void baselinesManualV1WithEmptyFlywayHistoryThenRunsV2() throws SQLException, IOException {
        String schema = "app_user_manual_v1";
        // Use Flyway only to create its exact history-table structure, then recreate
        // the V1 table manually to reproduce a database whose history has no rows.
        migrate(schema, "1");
        execute("DROP TABLE " + schema + ".app_user");
        execute("TRUNCATE TABLE " + schema + ".flyway_schema_history");
        assertEquals(0, count("SELECT count(*) FROM " + schema + ".flyway_schema_history"));

        String v1;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V1__APP_USER.sql"))) {
            v1 = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            connection.setSchema(schema);
            statement.execute(v1);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .baselineVersion("1")
                .load();
        FlywayException failure = assertThrows(FlywayException.class, flyway::baseline);
        assertTrue(failure.getMessage().contains("already exists, and is empty"));
        assertEquals(0, count("SELECT count(*) FROM " + schema + ".flyway_schema_history"));

        // Flyway 13 refuses baseline() with an existing empty history table.
        // Removing only that empty table lets Flyway recreate it with a V1 baseline.
        execute("DROP TABLE " + schema + ".flyway_schema_history");
        assertTrue(flyway.baseline().successfullyBaselined);
        assertTrue(flyway.migrate().success);
        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version = '1' AND type = 'BASELINE' AND success"));
        assertEquals(1, count("SELECT count(*) FROM " + schema
                + ".flyway_schema_history WHERE version = '2' AND success"));
        assertEquals(1, insertAccount(schema, "manual@example.com", "OWNER"));
    }

    private void migrate(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) {
            configuration.target(target);
        }
        Flyway flyway = configuration.load();
        assertTrue(flyway.migrate().success);
    }

    private long insertAccount(String schema, String email, String role) throws SQLException {
        String sql = "INSERT INTO " + schema + ".app_user "
                + "(id, email_normalized, password_hash, role) VALUES (?, ?, ?, ?) RETURNING user_number";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, email);
            statement.setString(3, "test-hash");
            statement.setString(4, role);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private void insertLegacyAccount(String schema, long number, String email) throws SQLException {
        String sql = "INSERT INTO " + schema + ".app_user "
                + "(id, user_number, email_normalized, password_hash, role, active, version, created_at) "
                + "VALUES (?, ?, ?, 'old-hash', 'TECHNICIAN', true, 0, TIMESTAMP '2026-01-02 03:04:05')";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setLong(2, number);
            statement.setString(3, email);
            statement.executeUpdate();
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void assertSqlState(String expectedState, SqlOperation operation) {
        SQLException failure = assertThrows(SQLException.class, operation::run);
        assertEquals(expectedState, failure.getSQLState());
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }
}
