package pl.instalflow.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Atomic, bounded limit per account and source address, local to one JVM. */
@Component
class LoginAttemptLimiter {
    private static final int MAX_BUCKETS = 10_000;
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;

    @Autowired
    LoginAttemptLimiter(@Value("${instalflow.auth.max-failed-attempts:5}") int maxAttempts,
            @Value("${instalflow.auth.failed-attempt-window:15m}") Duration window, Clock clock) {
        if (maxAttempts < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Invalid login attempt limit");
        }
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Reserve before checking the password; concurrent requests cannot bypass the limit. */
    synchronized boolean tryAcquire(String email, String address) {
        Instant now = clock.instant();
        String emailKey = "email:" + email;
        String ipKey = "ip:" + address;
        Attempt emailAttempt = current(emailKey, now);
        Attempt ipAttempt = current(ipKey, now);
        if ((emailAttempt != null && emailAttempt.count() >= maxAttempts)
                || (ipAttempt != null && ipAttempt.count() >= maxAttempts)) {
            return false;
        }
        int needed = (emailAttempt == null ? 1 : 0) + (ipAttempt == null ? 1 : 0);
        if (attempts.size() + needed > MAX_BUCKETS) {
            attempts.entrySet().removeIf(entry -> expired(entry.getValue(), now));
            if (attempts.size() + needed > MAX_BUCKETS) {
                return false; // Fail closed without resetting other counters.
            }
        }
        attempts.put(emailKey, increment(emailAttempt, now));
        attempts.put(ipKey, increment(ipAttempt, now));
        return true;
    }

    /** Release only the successful reservation; earlier failures stay counted. */
    synchronized void succeeded(String email, String address) {
        release("email:" + email);
        release("ip:" + address);
    }

    private Attempt current(String key, Instant now) {
        Attempt attempt = attempts.get(key);
        if (attempt != null && expired(attempt, now)) {
            attempts.remove(key);
            return null;
        }
        return attempt;
    }

    private boolean expired(Attempt attempt, Instant now) {
        return !attempt.started().plus(window).isAfter(now);
    }

    private static Attempt increment(Attempt previous, Instant now) {
        return previous == null ? new Attempt(now, 1) : new Attempt(previous.started(), previous.count() + 1);
    }

    private void release(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) return;
        if (attempt.count() <= 1) attempts.remove(key);
        else attempts.put(key, new Attempt(attempt.started(), attempt.count() - 1));
    }

    private record Attempt(Instant started, int count) { }
}
