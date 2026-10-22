package pl.instalflow;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class FlywayMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void migratesEmptyDatabaseOnlyOnceAndUpgradesExistingData() throws SQLException {
        try (ConfigurableApplicationContext ignored = start("f03_ok", "classpath:db/f03/valid")) {
            assertEquals(1, count("SELECT count(*) FROM f03_ok.flyway_schema_history WHERE version = '1' AND success"));
            assertEquals(1, count("SELECT count(*) FROM f03_ok.probe"));
            execute();
        }

        try (ConfigurableApplicationContext ignored = start("f03_ok", "classpath:db/f03/valid")) {
            assertEquals(1, count("SELECT count(*) FROM f03_ok.flyway_schema_history WHERE version = '1' AND success"));
            assertEquals(0, count("SELECT count(*) FROM f03_ok.flyway_schema_history WHERE version = '2'"));
            assertEquals(2, count("SELECT count(*) FROM f03_ok.probe"));
            assertEquals(1, count("SELECT count(*) FROM f03_ok.probe WHERE id = 2 AND value = 'preserved'"));
        }

        try (ConfigurableApplicationContext ignored = start("f03_ok",
                "classpath:db/f03/valid,classpath:db/f03/upgrade")) {
            assertEquals(1, count("SELECT count(*) FROM f03_ok.flyway_schema_history WHERE version = '1' AND success"));
            assertEquals(1, count("SELECT count(*) FROM f03_ok.flyway_schema_history WHERE version = '2' AND success"));
            assertEquals(2, count("SELECT count(*) FROM f03_ok.probe WHERE note = ''"));
            assertEquals(1, count("SELECT count(*) FROM f03_ok.probe WHERE id = 2 AND value = 'preserved'"));
        }
    }

    @Test
    void invalidMigrationStopsApplicationStartup() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> start("f03_bad", "classpath:db/f03/invalid"));
        assertTrue(hasFlywayCause(failure),
                () -> "Expected a Flyway failure, got: " + failure);
    }

    private ConfigurableApplicationContext start(String schema, String location) {
        return new SpringApplicationBuilder(InstalFlowApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("f03")
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.flyway.locations=" + location,
                        "--spring.flyway.schemas=" + schema,
                        "--spring.flyway.default-schema=" + schema);
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getInt(1);
        }
    }

    private void execute() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO f03_ok.probe (id, value) VALUES (2, 'preserved')");
        }
    }

    private boolean hasFlywayCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof FlywayException) {
                return true;
            }
        }
        return false;
    }
}
