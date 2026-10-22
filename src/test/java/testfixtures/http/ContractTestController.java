package testfixtures.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/** Only loaded explicitly by HTTP contract tests, outside the application's component scan. */
@RestController
public class ContractTestController {
    private final Clock clock;

    public ContractTestController(Clock clock) {
        this.clock = clock;
    }

    @PostMapping("/contract-test/values")
    public Values echo(@RequestBody Values input) {
        return input;
    }

    @GetMapping("/contract-test/values")
    public Values values() {
        return new Values(new BigDecimal("1E+4"), new BigDecimal("0.00000000000000000000001"), clock.instant());
    }

    public record Values(BigDecimal amount, BigDecimal quantity, Instant occurredAt) {
    }

    @PostMapping("/contract-test/scalars")
    public Scalars echoScalars(@RequestBody Scalars input) {
        return input;
    }

    public record Scalars(Long version, TestRole role, String password) {
    }

    public enum TestRole {
        OWNER, OFFICE, TECHNICIAN
    }
}
