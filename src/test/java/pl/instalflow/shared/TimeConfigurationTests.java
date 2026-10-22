package pl.instalflow.shared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import pl.instalflow.shared.config.TimeConfiguration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigurationTests {
    @Test
    void defaultClockUsesUtcAndActualTime() {
        Instant before = Instant.now();
        new ApplicationContextRunner().withUserConfiguration(TimeConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            Clock clock = context.getBean(Clock.class);
            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(clock.instant()).isBetween(before, Instant.now());
        });
    }

    @Test
    void suppliedFixedClockReplacesDefaultClock() {
        new ApplicationContextRunner().withUserConfiguration(FixedTime.class, TimeConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).instant()).isEqualTo(Instant.parse("2026-10-22T12:00:00Z"));
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
