package pl.instalflow.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import pl.instalflow.shared.config.JsonContractConfiguration;
import pl.instalflow.shared.config.TimeConfiguration;
import testfixtures.http.ContractTestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {JsonContractIntegrationTests.FixedTime.class, TimeConfiguration.class,
        JsonContractConfiguration.class, ContractTestController.class})
class JsonContractIntegrationTests {
    @Autowired
    MockMvc mvc;

    @Test
    void actualMvcConvertersPreserveDecimalStringsPrecisionAndScale() throws Exception {
        mvc.perform(post("/contract-test/values").contentType(MediaType.APPLICATION_JSON).content("""
                        {"amount":"123456789012345678901234567890.1200","quantity":"2.500",
                         "occurredAt":"2026-10-22T14:15:16.123456789+02:00"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").isString())
                .andExpect(jsonPath("$.amount").value("123456789012345678901234567890.1200"))
                .andExpect(jsonPath("$.quantity").isString())
                .andExpect(jsonPath("$.quantity").value("2.500"))
                .andExpect(jsonPath("$.occurredAt").value("2026-10-22T12:15:16.123456789Z"));
    }

    @Test
    void responseUsesPlainDecimalStringsAndInjectedFixedUtcClock() throws Exception {
        mvc.perform(get("/contract-test/values"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("10000"))
                .andExpect(jsonPath("$.quantity").value("0.00000000000000000000001"))
                .andExpect(jsonPath("$.occurredAt").value("2026-10-22T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2026-10-22T12:00:00Z\"", "\"2026-10-22T14:00:00+02:00\"",
            "\"2026-10-22T07:00:00-05:00\""})
    void equivalentOffsetTimestampsHaveTheSameUtcOutput(String timestamp) throws Exception {
        mvc.perform(post("/contract-test/values").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"2.50\",\"quantity\":\"2.500\",\"occurredAt\":" + timestamp + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("2.50"))
                .andExpect(jsonPath("$.quantity").value("2.500"))
                .andExpect(jsonPath("$.occurredAt").value("2026-10-22T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.50", "2", "1e3", "true", "{}", "[]", "\"1e3\"", "\"NaN\"",
            "\"Infinity\"", "\"\"", "\"2,50\"", "\" 2.50\"", "\"+2.50\"", "\"02.50\"", "\".5\""})
    void rejectsNumericTokensAndNonPlainDecimals(String invalidDecimal) throws Exception {
        mvc.perform(post("/contract-test/values").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + invalidDecimal + ",\"quantity\":\"2.500\","
                                + "\"occurredAt\":\"2026-10-22T12:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quantitiesFollowTheSameStringContractAsAmounts() throws Exception {
        mvc.perform(post("/contract-test/values").contentType(MediaType.APPLICATION_JSON).content("""
                        {"amount":"2.50","quantity":2.500,"occurredAt":"2026-10-22T12:00:00Z"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2026-10-22T12:00:00\"", "\"2026-10-22\"", "\"not-a-time\"", "0",
            "\"2026-10-22T12:00:00+01:00:30\"", "\"2026-10-22T12:00+01:00\"",
            "\"2026-10-22T12:00:00+0100\"", "\"+12026-10-22T12:00:00Z\"",
            "\"2026-10-22T12:00:00+01:00[Europe/Warsaw]\""})
    void rejectsTimestampWithoutOffsetOrWithWrongToken(String timestamp) throws Exception {
        mvc.perform(post("/contract-test/values").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"2.50\",\"quantity\":\"2.500\",\"occurredAt\":" + timestamp + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsScalarTokensMatchingTheApiSchema() throws Exception {
        mvc.perform(post("/contract-test/scalars").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"role\":\"OFFICE\",\"password\":\"123456789012\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.role").value("OFFICE"))
                .andExpect(jsonPath("$.password").value("123456789012"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"version\":0.7,\"role\":\"OFFICE\",\"password\":\"secret\"}",
            "{\"version\":0.0,\"role\":\"OFFICE\",\"password\":\"secret\"}",
            "{\"version\":\"0\",\"role\":\"OFFICE\",\"password\":\"secret\"}",
            "{\"version\":0,\"role\":1,\"password\":\"secret\"}",
            "{\"version\":0,\"role\":\"1\",\"password\":\"secret\"}",
            "{\"version\":0,\"role\":\"OFFICE\",\"password\":123456789012}",
            "{\"version\":0,\"role\":\"OFFICE\",\"password\":12.34}",
            "{\"version\":0,\"role\":\"OFFICE\",\"password\":true}"})
    void rejectsImplicitScalarConversions(String body) throws Exception {
        mvc.perform(post("/contract-test/scalars").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
