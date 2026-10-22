package pl.instalflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.Yaml;
import pl.instalflow.identity.application.UserService;
import pl.instalflow.identity.application.dto.*;
import pl.instalflow.identity.application.error.UserNotFoundException;
import pl.instalflow.identity.domain.RoleEnum;
import pl.instalflow.identity.web.ApiExceptionHandler;
import pl.instalflow.identity.web.RequestIdFilter;
import pl.instalflow.identity.web.UserController;
import pl.instalflow.shared.config.JsonContractConfiguration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Checks the published contract against real MVC serialization and controller mappings. */
class OpenApiContractTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode document;
    private static String yaml;
    private UserService users;
    private MockMvc mvc;

    @BeforeAll
    static void loadContract() throws Exception {
        try (InputStream input = OpenApiContractTests.class.getResourceAsStream("/static/openapi.yaml")) {
            assertThat(input).isNotNull();
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            document = JSON.valueToTree(new Yaml().load(yaml));
        }
    }

    @BeforeEach
    void mvc() {
        users = mock(UserService.class);
        try (var context = new AnnotationConfigApplicationContext(JsonContractConfiguration.class)) {
            var mapper = JsonMapper.builder();
            context.getBeanProvider(JacksonModule.class).orderedStream().forEach(mapper::addModule);
            context.getBeanProvider(JsonMapperBuilderCustomizer.class).orderedStream()
                    .forEach(customizer -> customizer.customize(mapper));
            mvc = MockMvcBuilders.standaloneSetup(new UserController(users))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                    .addFilters(new RequestIdFilter()).build();
        }
    }

    @Test
    void openApiParsesWithoutWarningsAndEveryReferenceIsLocalAndResolvable() {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        var result = new OpenAPIV3Parser().readContents(yaml, null, options);
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getMessages()).isNullOrEmpty();
        for (JsonNode reference : document.findValues("$ref")) {
            String pointer = reference.asText();
            assertThat(pointer).startsWith("#/");
            assertThat(document.at(pointer.substring(1)).isMissingNode()).as(pointer).isFalse();
        }
    }

    @Test
    void everyImplementedIdentityRouteAndOnlyImplementedRoutesArePublished() throws Exception {
        Set<String> implemented = new HashSet<>();
        for (Class<?> controller : List.of(UserController.class,
                Class.forName("pl.instalflow.identity.security.AuthController"))) {
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            for (var method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] suffixes = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                for (String suffix : suffixes) {
                    for (RequestMethod verb : mapping.method()) {
                        implemented.add(verb.name().toLowerCase() + " " + root.path()[0] + suffix);
                    }
                }
            }
        }
        Set<String> published = new HashSet<>();
        document.path("paths").fields().forEachRemaining(path -> path.getValue().fieldNames()
                .forEachRemaining(verb -> {
                    if (Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace").contains(verb)) {
                        published.add(verb + " " + path.getKey());
                    }
                }));
        assertThat(published).containsExactlyInAnyOrderElementsOf(implemented);
    }

    @Test
    void recordFieldsAndRequiredRequestFieldsMatchSchemas() throws Exception {
        for (Class<?> type : List.of(CreateUserRequest.class, ChangeRoleRequest.class,
                ChangePasswordRequest.class, VersionRequest.class, UserResponse.class,
                TechnicianResponse.class,
                Class.forName("pl.instalflow.identity.security.AuthController$LoginRequest"),
                Class.forName("pl.instalflow.identity.security.AuthController$CsrfResponse"),
                Class.forName("pl.instalflow.identity.security.AuthController$MeResponse"))) {
            JsonNode schema = document.at("/components/schemas/" + type.getSimpleName());
            Set<String> fields = Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet());
            assertThat(iterableSet(schema.path("properties").fieldNames())).as(type.getName()).isEqualTo(fields);
            Set<String> required = new HashSet<>();
            for (var field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(NotBlank.class)
                        || type.getSimpleName().endsWith("Response")) required.add(field.getName());
            }
            assertThat(textSet(schema.path("required"))).as(type.getName() + " required").isEqualTo(required);
        }
    }

    @Test
    void allPublishedSchemaExamplesConformAndInvalidWireValuesFail() throws Exception {
        var schemas = document.at("/components/schemas").fields();
        while (schemas.hasNext()) {
            var entry = schemas.next();
            if (entry.getValue().has("example")) {
                assertValid(entry.getKey(), entry.getValue().get("example").toString());
            }
        }
        for (String invalid : List.of("1e3", "+1", "01", " 1", "1.", ".5")) {
            assertThat(schema("DecimalValue").validate(JSON.writeValueAsString(invalid), InputFormat.JSON)).isNotEmpty();
        }
        assertThat(schema("DecimalValue").validate("123.45", InputFormat.JSON)).isNotEmpty();
        assertThat(schema("Version").validate("-1", InputFormat.JSON)).isNotEmpty();
        assertThat(schema("VersionRequest").validate("{}", InputFormat.JSON)).isNotEmpty();
        assertThat(schema("Timestamp").validate("\"2026-10-22T10:15:30\"", InputFormat.JSON)).isNotEmpty();
        assertThat(schema("Timestamp").validate("\"2026-10-22T10:15:30+01:00:30\"", InputFormat.JSON)).isNotEmpty();
        assertThat(schema("Timestamp").validate("\"2026-10-22T10:15:30.1234567890Z\"", InputFormat.JSON)).isNotEmpty();
        assertValid("Timestamp", "\"2026-10-22t10:15:30z\"");
        assertThat(schema("TechnicianResponse").validate("{\"id\":\"ec7bbce0-c246-4573-862c-1c7d7db2a71d\",\"employeeNumber\":\"0002\",\"email\":\"secret@example.com\"}", InputFormat.JSON)).isNotEmpty();
        ObjectNode implicitTypeProblem = document.at("/components/schemas/Problem/example").deepCopy();
        implicitTypeProblem.remove("type");
        assertValid("Problem", implicitTypeProblem.toString());
        for (String required : List.of("title", "status", "detail", "instance", "code", "requestId")) {
            ObjectNode missingRequired = implicitTypeProblem.deepCopy();
            missingRequired.remove(required);
            assertThat(schema("Problem").validate(missingRequired.toString(), InputFormat.JSON))
                    .as("Problem requires " + required).isNotEmpty();
        }
    }

    @Test
    void userCreateReadListAndRoleChangeResponsesConformToPublishedSchemas() throws Exception {
        UUID id = UUID.randomUUID();
        UserResponse user = new UserResponse(id, "0002", "worker@example.com", RoleEnum.TECHNICIAN,
                true, 0L, Instant.parse("2026-10-22T10:15:30Z"));
        when(users.create(any())).thenReturn(user);
        when(users.get(id)).thenReturn(user);
        when(users.changeRole(eq(id), any())).thenReturn(user);
        when(users.list(any())).thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(users.technicians(any())).thenReturn(new PageImpl<>(List.of(new TechnicianResponse(id, "0002")), PageRequest.of(0, 20), 1));
        MvcResult created = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"worker@example.com\",\"password\":\"Example-passphrase-123!\",\"role\":\"TECHNICIAN\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/users/" + id)).andReturn();
        assertResponse("/api/v1/users", "post", created);
        assertResponse("/api/v1/users/{id}", "get", mvc.perform(get("/api/v1/users/" + id)).andExpect(status().isOk()).andReturn());
        assertResponse("/api/v1/users", "get", mvc.perform(get("/api/v1/users")).andExpect(status().isOk()).andReturn());
        assertResponse("/api/v1/users/technicians", "get", mvc.perform(get("/api/v1/users/technicians")).andExpect(status().isOk()).andReturn());
        assertResponse("/api/v1/users/{id}", "patch", mvc.perform(patch("/api/v1/users/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OFFICE\",\"version\":0}"))
                .andExpect(status().isOk()).andReturn());
        verify(users).create(new CreateUserRequest("worker@example.com", "Example-passphrase-123!", RoleEnum.TECHNICIAN));
        verify(users).changeRole(id, new ChangeRoleRequest(RoleEnum.OFFICE, 0L));
    }

    @Test
    void mutationBodiesAndNoContentResponsesMatchPublishedContract() throws Exception {
        UUID id = UUID.randomUUID();
        MvcResult password = mvc.perform(post("/api/v1/users/" + id + "/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Replacement-passphrase!\",\"version\":2}"))
                .andExpect(status().isNoContent()).andReturn();
        MvcResult deactivate = mvc.perform(post("/api/v1/users/" + id + "/deactivate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":3}"))
                .andExpect(status().isNoContent()).andReturn();
        assertResponse("/api/v1/users/{id}/password", "post", password);
        assertResponse("/api/v1/users/{id}/deactivate", "post", deactivate);
        verify(users).changePassword(id, new ChangePasswordRequest("Replacement-passphrase!", 2L));
        verify(users).deactivate(id, new VersionRequest(3L));
    }

    @Test
    void validationErrorsHaveDocumentedProblemFieldsAndViolations() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\",\"role\":null}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertResponse("/api/v1/users", "post", result);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.path("violations").size()).isEqualTo(3);
        assertThat(body.path("requestId").asText()).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        verifyNoInteractions(users);
    }

    @Test
    void missingUserAndInvalidPaginationConformToProblemContract() throws Exception {
        UUID id = UUID.randomUUID();
        when(users.get(id)).thenThrow(new UserNotFoundException(id));
        MvcResult missing = mvc.perform(get("/api/v1/users/" + id)).andExpect(status().isNotFound()).andReturn();
        assertResponse("/api/v1/users/{id}", "get", missing);
        assertThat(JSON.readTree(missing.getResponse().getContentAsString()).path("code").asText()).isEqualTo("USER_NOT_FOUND");
        MvcResult pagination = mvc.perform(get("/api/v1/users?size=101")).andExpect(status().isBadRequest()).andReturn();
        assertResponse("/api/v1/users", "get", pagination);
    }

    @Test
    void wrongVersionRoleAndPasswordJsonTypesAreRejectedBeforeCallingTheService() throws Exception {
        UUID id = UUID.randomUUID();
        for (String version : List.of("0.7", "\"0\"", "true", "{}", "[]")) {
            String body = "{\"version\":" + version + "}";
            assertThat(schema("VersionRequest").validate(body, InputFormat.JSON))
                    .as("schema rejects version " + version).isNotEmpty();
            MvcResult response = mvc.perform(post("/api/v1/users/" + id + "/deactivate")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn();
            assertResponse("/api/v1/users/{id}/deactivate", "post", response);
        }
        for (String role : List.of("1", "\"1\"", "true", "{}", "[]")) {
            String body = "{\"email\":\"worker@example.com\",\"password\":\"Example-passphrase-123!\",\"role\":" + role + "}";
            assertThat(schema("CreateUserRequest").validate(body, InputFormat.JSON))
                    .as("schema rejects role " + role).isNotEmpty();
            MvcResult response = mvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn();
            assertResponse("/api/v1/users", "post", response);
        }
        for (String password : List.of("123", "true", "{}", "[]")) {
            String body = "{\"email\":\"worker@example.com\",\"password\":" + password + ",\"role\":\"OFFICE\"}";
            assertThat(schema("CreateUserRequest").validate(body, InputFormat.JSON))
                    .as("schema rejects password " + password).isNotEmpty();
            MvcResult response = mvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn();
            assertResponse("/api/v1/users", "post", response);
        }
        verifyNoInteractions(users);
    }

    private static void assertResponse(String path, String method, MvcResult result) throws Exception {
        JsonNode response = resolve(document.path("paths").path(path).path(method).path("responses")
                .path(Integer.toString(result.getResponse().getStatus())));
        assertThat(response.isMissingNode()).as(method + " " + path + " status").isFalse();
        if (!response.has("content")) {
            assertThat(result.getResponse().getContentAsString()).isEmpty();
            return;
        }
        String media = result.getResponse().getContentType().split(";")[0];
        JsonNode shape = response.path("content").path(media).path("schema");
        assertThat(shape.isMissingNode()).as("documented content type").isFalse();
        String schema = shape.path("$ref").asText().substring("#/components/schemas/".length());
        assertValid(schema, result.getResponse().getContentAsString());
    }

    private static JsonNode resolve(JsonNode node) {
        return node.has("$ref") ? document.at(node.path("$ref").asText().substring(1)) : node;
    }

    private static void assertValid(String schema, String json) throws Exception {
        var errors = schema(schema).validate(json, InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        assertThat(errors).as(schema + " response/example: " + json).isEmpty();
    }

    private static Schema schema(String name) throws Exception {
        String location = "https://instalflow.invalid/contracts/" + name;
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        envelope.put("$ref", "#/components/schemas/" + name);
        envelope.set("components", document.path("components"));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(location, envelope.toString())));
        Schema schema = registry.getSchema(SchemaLocation.of(location));
        schema.initializeValidators();
        return schema;
    }

    private static Set<String> iterableSet(java.util.Iterator<String> values) {
        Set<String> result = new HashSet<>();
        values.forEachRemaining(result::add);
        return result;
    }

    private static Set<String> textSet(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
