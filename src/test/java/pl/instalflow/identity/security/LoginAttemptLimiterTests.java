package pl.instalflow.identity.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginAttemptLimiterTests {

    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, Duration.ofMinutes(15),
            Clock.fixed(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void simultaneousRequestsCannotReserveMoreThanFiveSlots() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.tryAcquire("same@example.com", "198.51.100.1");
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) admitted++;
            }
            assertThat(admitted).isEqualTo(5);
        }
    }

    @Test
    void successfulLoginReleasesOnlyItsOwnReservation() {
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire("same@example.com", "198.51.100.2")).isTrue();
        }
        assertThat(limiter.tryAcquire("same@example.com", "198.51.100.2")).isTrue();
        limiter.succeeded("same@example.com", "198.51.100.2");

        assertThat(limiter.tryAcquire("same@example.com", "198.51.100.2")).isTrue();
        assertThat(limiter.tryAcquire("same@example.com", "198.51.100.2")).isFalse();
    }

    @Test
    void limitResetsExactlyAtWindowBoundaryWithoutExtendingOnRejectedAttempts() {
        Instant start = Instant.parse("2026-10-22T08:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(start);
        LoginAttemptLimiter expiring = new LoginAttemptLimiter(5, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 5; i++) {
            assertThat(expiring.tryAcquire("same@example.com", "198.51.100.4")).isTrue();
        }
        assertThat(expiring.tryAcquire("same@example.com", "198.51.100.4")).isFalse();

        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(15)).minusMillis(1));
        assertThat(expiring.tryAcquire("same@example.com", "198.51.100.4")).isFalse();

        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(15)));
        for (int i = 0; i < 5; i++) {
            assertThat(expiring.tryAcquire("same@example.com", "198.51.100.4")).isTrue();
        }
        assertThat(expiring.tryAcquire("same@example.com", "198.51.100.4")).isFalse();
    }

    @Test
    void changingEmailDoesNotResetAddressLimit() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("different" + i + "@example.com", "198.51.100.3")).isTrue();
        }
        assertThat(limiter.tryAcquire("another@example.com", "198.51.100.3")).isFalse();
    }
}
