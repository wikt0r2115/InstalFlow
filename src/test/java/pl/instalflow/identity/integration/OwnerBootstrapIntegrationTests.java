package pl.instalflow.identity.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pl.instalflow.InstalFlowApplication;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class OwnerBootstrapIntegrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void bootstrapCreatesOneOwnerAndDoesNotResetItOnRestart() {
        String originalHash;
        try (ConfigurableApplicationContext first = start("first-password-123!")) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT role FROM app_user", String.class)).isEqualTo("OWNER");
            assertThat(jdbc.queryForObject("SELECT user_number FROM app_user", Long.class)).isEqualTo(1L);
            originalHash = jdbc.queryForObject("SELECT password_hash FROM app_user", String.class);
            assertThat(originalHash).isNotEqualTo("first-password-123!");
        }

        try (ConfigurableApplicationContext second = start("changed-password-123!")) {
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user", String.class))
                    .isEqualTo(originalHash);
        }
    }

    @Test
    void demoProfileStartsWithMigratedDatabaseAndSecureSessionCookies() throws Exception {
        try (ConfigurableApplicationContext context = start("demo-password-123!")) {
            assertThat(context.getEnvironment().getActiveProfiles()).contains("demo");
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
                    .isEqualTo(3);
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().allValues("Set-Cookie"))
                        .anySatisfy(cookie -> assertThat(cookie)
                                .startsWith("SESSION=")
                                .contains("Secure", "HttpOnly", "SameSite=Strict"));
            }
        }
    }

    @Test
    void bootstrapRejectsIncompleteCredentials() {
        assertThrows(RuntimeException.class, () -> start(""));
    }

    private ConfigurableApplicationContext start(String password) {
        return new SpringApplicationBuilder(InstalFlowApplication.class)
                .profiles("demo")
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0",
                        "--server.servlet.session.cookie.secure=true",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--instalflow.bootstrap.owner-email=owner@example.com",
                        "--instalflow.bootstrap.owner-password=" + password);
    }
}
