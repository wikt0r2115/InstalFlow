package pl.instalflow.identity.integration;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class IdentityApiIntegrationTests {

    private static final String API = "/api/v1";
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String OWNER_PASSWORD = "Owner-test-password-123!";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("instalflow.bootstrap.owner-email", () -> OWNER_EMAIL);
        registry.add("instalflow.bootstrap.owner-password", () -> OWNER_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void retainOnlyBootstrapOwner() {
        jdbc.update("DELETE FROM app_user WHERE email_normalized <> ?", OWNER_EMAIL);
        jdbc.update("UPDATE app_user SET role = 'OWNER', active = true WHERE email_normalized = ?", OWNER_EMAIL);
    }

    @Test
    void ownerCanCreateAccountsAndResponsesNeverExposePasswordHash() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        String office = create(owner, " Office@Example.com ", "OFFICE");
        String technician = create(owner, "Tech@Example.com", "TECHNICIAN");

        assertThat(office).doesNotContain("password", "passwordHash", "password_hash");
        assertThat(JsonPath.<String>read(office, "$.email")).isEqualTo("office@example.com");
        assertThat(JsonPath.<String>read(office, "$.employeeNumber")).matches("[0-9]{4,}");
        assertThat(UUID.fromString(JsonPath.read(office, "$.id"))).isNotNull();
        assertThat(JsonPath.<String>read(technician, "$.employeeNumber"))
                .isNotEqualTo(JsonPath.<String>read(office, "$.employeeNumber"));

        String list = body(mvc.perform(get(API + "/users").cookie(owner.cookie))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).contains("office@example.com", "tech@example.com")
                .doesNotContain("passwordHash", "password_hash", OWNER_PASSWORD);
    }

    @Test
    void officeGetsOnlyActiveTechnicianNumbersAndCannotReadOtherEmails() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        String office = create(owner, "office@example.com", "OFFICE");
        String technician = create(owner, "tech@example.com", "TECHNICIAN");
        String inactive = create(owner, "inactive@example.com", "TECHNICIAN");
        String inactiveId = JsonPath.read(inactive, "$.id");
        long inactiveVersion = number(inactive, "$.version");
        mvc.perform(withCsrf(owner, post(API + "/users/" + inactiveId + "/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + inactiveVersion + "}")))
                .andExpect(status().isNoContent());

        Session officeSession = login("office@example.com", "Test-password-123!");
        String directory = body(mvc.perform(get(API + "/users/technicians")
                .cookie(officeSession.cookie)).andExpect(status().isOk()).andReturn());
        assertThat(directory).contains(JsonPath.<String>read(technician, "$.employeeNumber"))
                .doesNotContain("tech@example.com", "inactive@example.com", "passwordHash", "password_hash")
                .doesNotContain(JsonPath.<String>read(inactive, "$.employeeNumber"));

        String officeId = JsonPath.read(office, "$.id");
        mvc.perform(get(API + "/users").cookie(officeSession.cookie))
                .andExpect(status().isForbidden());
        mvc.perform(get(API + "/users/" + officeId).cookie(officeSession.cookie))
                .andExpect(status().isForbidden());
        Session technicianSession = login("tech@example.com", "Test-password-123!");
        mvc.perform(get(API + "/users/technicians").cookie(technicianSession.cookie))
                .andExpect(status().isForbidden());
        String me = body(mvc.perform(get(API + "/auth/me").cookie(officeSession.cookie))
                .andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<String>read(me, "$.email")).isEqualTo("office@example.com");
    }

    @Test
    void emailRemainsReservedAfterDeactivation() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        String created = create(owner, "Case@Example.com", "TECHNICIAN");
        String id = JsonPath.read(created, "$.id");
        mvc.perform(withCsrf(owner, post(API + "/users/" + id + "/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + number(created, "$.version") + "}")))
                .andExpect(status().isNoContent());

        mvc.perform(withCsrf(owner, post(API + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" case@example.com \",\"password\":\"Test-password-123!\",\"role\":\"OFFICE\"}")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email_normalized = 'case@example.com'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void authenticationAuthorizationAndCsrfAreEnforced() throws Exception {
        mvc.perform(get(API + "/users")).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.com\",\"password\":\"Test-password-123!\",\"role\":\"OFFICE\"}"))
                .andExpect(status().isForbidden());

        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        mvc.perform(post(API + "/users").cookie(owner.cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"password\":\"Test-password-123!\",\"role\":\"OFFICE\"}"))
                .andExpect(status().isForbidden());

        create(owner, "office@example.com", "OFFICE");
        create(owner, "tech@example.com", "TECHNICIAN");
        for (String email : List.of("office@example.com", "tech@example.com")) {
            Session employee = login(email, "Test-password-123!");
            mvc.perform(withCsrf(employee, post(API + "/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"blocked@example.com\",\"password\":\"Test-password-123!\",\"role\":\"TECHNICIAN\"}")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void invalidRequestsAndMissingUsersGetSpecificClientErrors() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        mvc.perform(withCsrf(owner, post(API + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad-email\",\"password\":\"Test-password-123!\",\"role\":\"OFFICE\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(withCsrf(owner, post(API + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"valid@example.com\",\"password\":\"Test-password-123!\",\"role\":\"ADMIN\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/users/" + UUID.randomUUID()).cookie(owner.cookie))
                .andExpect(status().isNotFound());
        mvc.perform(get(API + "/users?page=-1").cookie(owner.cookie))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/users/technicians?size=101").cookie(owner.cookie))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/users?sort=passwordHash").cookie(owner.cookie))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/users/technicians?sort=email").cookie(owner.cookie))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedHttpMethodsAndContentTypesKeepTheirClientErrorStatus() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        MvcResult wrongMethod = mvc.perform(withCsrf(owner, delete(API + "/users")))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertSafeProblem(wrongMethod, 405);

        MvcResult wrongContentType = mvc.perform(withCsrf(owner, post(API + "/users")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("private-request-content")))
                .andExpect(status().isUnsupportedMediaType()).andReturn();
        assertSafeProblem(wrongContentType, 415);
        assertThat(body(wrongContentType)).doesNotContain("private-request-content");
    }

    @Test
    void loginRotatesSessionIdAndRejectsTheAnonymousSessionCookie() throws Exception {
        MvcResult csrf = mvc.perform(get(API + "/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        Cookie anonymousCookie = csrf.getResponse().getCookie("SESSION");
        assertThat(anonymousCookie).isNotNull();
        String token = JsonPath.read(body(csrf), "$.token");

        MvcResult login = mvc.perform(post(API + "/auth/login")
                        .cookie(anonymousCookie).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"" + OWNER_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie authenticatedCookie = login.getResponse().getCookie("SESSION");
        assertThat(authenticatedCookie).isNotNull();
        assertThat(authenticatedCookie.getValue()).isNotEqualTo(anonymousCookie.getValue());
        mvc.perform(get(API + "/auth/me").cookie(anonymousCookie))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/auth/me").cookie(authenticatedCookie))
                .andExpect(status().isOk());
    }

    @Test
    void expiredSessionCannotAuthenticateEvenBeforeScheduledCleanup() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        String id = new String(java.util.Base64.getDecoder().decode(owner.cookie.getValue()),
                java.nio.charset.StandardCharsets.UTF_8);
        long expiredAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31);
        assertThat(jdbc.update("UPDATE spring_session SET last_access_time = ?, expiry_time = ? WHERE session_id = ?",
                expiredAt, expiredAt, id)).isEqualTo(1);

        MvcResult response = mvc.perform(get(API + "/auth/me").cookie(owner.cookie))
                .andExpect(status().isUnauthorized()).andReturn();
        assertSafeProblem(response, 401);
    }

    @Test
    void loginRequiresCsrfAndLimitsRepeatedBadPasswords() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        create(owner, "limited@example.com", "TECHNICIAN");
        MvcResult csrf = mvc.perform(get(API + "/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = csrf.getResponse().getCookie("SESSION");
        String token = JsonPath.read(body(csrf), "$.token");
        String wrongCredentials = "{\"email\":\"limited@example.com\",\"password\":\"wrong-password\"}";

        mvc.perform(post(API + "/auth/login").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongCredentials))
                .andExpect(status().isForbidden());
        for (int attempt = 0; attempt < 5; attempt++) {
            String response = body(mvc.perform(post(API + "/auth/login").cookie(cookie)
                            .with(request -> { request.setRemoteAddr("198.51.100.77"); return request; })
                            .header("X-CSRF-TOKEN", token)
                            .contentType(MediaType.APPLICATION_JSON).content(wrongCredentials))
                    .andExpect(status().isUnauthorized()).andReturn());
            assertThat(response).contains("INVALID_CREDENTIALS")
                    .doesNotContain("password_hash", "passwordHash", "wrong-password");
        }
        mvc.perform(post(API + "/auth/login").cookie(cookie)
                        .with(request -> { request.setRemoteAddr("198.51.100.77"); return request; })
                        .header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongCredentials))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void staleVersionReturnsConflictAndRoleChangeRevokesSession() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        String technician = create(owner, "tech@example.com", "TECHNICIAN");
        String id = JsonPath.read(technician, "$.id");
        long version = number(technician, "$.version");
        Session employee = login("tech@example.com", "Test-password-123!");

        mvc.perform(withCsrf(owner, patch(API + "/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OFFICE\",\"version\":" + version + "}")))
                .andExpect(status().isOk());
        mvc.perform(withCsrf(owner, patch(API + "/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TECHNICIAN\",\"version\":" + version + "}")))
                .andExpect(status().isConflict());
        mvc.perform(get(API + "/auth/me").cookie(employee.cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeAndDeactivationRevokeExistingSessions() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        String office = create(owner, "office@example.com", "OFFICE");
        String id = JsonPath.read(office, "$.id");
        long version = number(office, "$.version");
        Session employee = login("office@example.com", "Test-password-123!");

        mvc.perform(withCsrf(owner, post(API + "/users/" + id + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Changed-password-123!\",\"version\":" + version + "}")))
                .andExpect(status().isNoContent());
        mvc.perform(get(API + "/auth/me").cookie(employee.cookie))
                .andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(owner, post(API + "/users/" + id + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Again-password-123!\",\"version\":" + version + "}")))
                .andExpect(status().isConflict());

        Session changed = login("office@example.com", "Changed-password-123!");
        long currentVersion = jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Long.class, UUID.fromString(id));
        mvc.perform(withCsrf(owner, post(API + "/users/" + id + "/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + currentVersion + "}")))
                .andExpect(status().isNoContent());
        mvc.perform(get(API + "/auth/me").cookie(changed.cookie))
                .andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(owner, post(API + "/users/" + id + "/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + currentVersion + "}")))
                .andExpect(status().isConflict());
    }

    @Test
    void soleActiveOwnerCannotBeDemotedOrDeactivated() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        UUID id = jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", UUID.class, OWNER_EMAIL);
        long version = jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Long.class, id);

        mvc.perform(withCsrf(owner, patch(API + "/users/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OFFICE\",\"version\":" + version + "}")))
                .andExpect(status().isConflict());
        mvc.perform(withCsrf(owner, post(API + "/users/" + id + "/deactivate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = 'OWNER' AND active", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentOwnerDemotionsPreserveAnActiveOwner() throws Exception {
        Session first = login(OWNER_EMAIL, OWNER_PASSWORD);
        String secondOwner = create(first, "second-owner@example.com", "OWNER");
        Session second = login("second-owner@example.com", "Test-password-123!");
        UUID firstId = jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized = ?", UUID.class, OWNER_EMAIL);
        long firstVersion = jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Long.class, firstId);
        String secondId = JsonPath.read(secondOwner, "$.id");
        long secondVersion = number(secondOwner, "$.version");

        var firstRequest = withCsrf(first, patch(API + "/users/" + secondId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"OFFICE\",\"version\":" + secondVersion + "}"));
        var secondRequest = withCsrf(second, patch(API + "/users/" + firstId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"OFFICE\",\"version\":" + firstVersion + "}"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> firstStatus = executor.submit(() -> {
                start.await();
                return mvc.perform(firstRequest).andReturn().getResponse().getStatus();
            });
            Future<Integer> secondStatus = executor.submit(() -> {
                start.await();
                return mvc.perform(secondRequest).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(firstStatus.get(15, TimeUnit.SECONDS), secondStatus.get(15, TimeUnit.SECONDS)))
                    .contains(200)
                    .anyMatch(status -> status == 409 || status == 401);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = 'OWNER' AND active", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        Session owner = login(OWNER_EMAIL, OWNER_PASSWORD);
        mvc.perform(post(API + "/auth/logout").cookie(owner.cookie))
                .andExpect(status().isForbidden());
        mvc.perform(get(API + "/auth/me").cookie(owner.cookie))
                .andExpect(status().isOk());
        mvc.perform(withCsrf(owner, post(API + "/auth/logout")))
                .andExpect(status().isNoContent());
        mvc.perform(get(API + "/auth/me").cookie(owner.cookie))
                .andExpect(status().isUnauthorized());
    }

    private String create(Session owner, String email, String role) throws Exception {
        return body(mvc.perform(withCsrf(owner, post(API + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Test-password-123!\",\"role\":\"" + role + "\"}")))
                .andExpect(status().isCreated()).andReturn());
    }

    private Session login(String email, String password) throws Exception {
        MvcResult csrf = mvc.perform(get(API + "/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = csrf.getResponse().getCookie("SESSION");
        assertThat(cookie).isNotNull();
        String token = JsonPath.read(body(csrf), "$.token");
        MvcResult result = mvc.perform(post(API + "/auth/login")
                        .cookie(cookie).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie authenticatedCookie = result.getResponse().getCookie("SESSION");
        return new Session(authenticatedCookie == null ? cookie : authenticatedCookie);
    }

    private MockHttpServletRequestBuilder withCsrf(Session session, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult csrf = mvc.perform(get(API + "/auth/csrf").cookie(session.cookie))
                .andExpect(status().isOk()).andReturn();
        Cookie refreshed = csrf.getResponse().getCookie("SESSION");
        if (refreshed != null) {
            session.cookie = refreshed;
        }
        return request.cookie(session.cookie)
                .header("X-CSRF-TOKEN", JsonPath.<String>read(body(csrf), "$.token"));
    }

    private static void assertSafeProblem(MvcResult result, int expectedStatus) throws Exception {
        String response = body(result);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(number(response, "$.status")).isEqualTo(expectedStatus);
        assertThat(JsonPath.<String>read(response, "$.code")).isNotBlank();
        String requestId = JsonPath.read(response, "$.requestId");
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo(requestId);
        assertThat(response).doesNotContain("stackTrace", "pl.instalflow", "org.springframework", "password_hash");
    }

    private static long number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static final class Session {
        private Cookie cookie;

        private Session(Cookie cookie) {
            this.cookie = cookie;
        }
    }
}
