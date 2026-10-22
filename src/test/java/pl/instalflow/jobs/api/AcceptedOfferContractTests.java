package pl.instalflow.jobs.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcceptedOfferContractTests {

    @Test
    void freezesTheAcceptedScopeIndependentlyOfTheCallersList() {
        var original = new ArrayList<>(List.of(line("1.125", "40.00")));
        var snapshot = command(original, "45.00");
        original.clear();
        assertThat(snapshot.scope()).hasSize(1);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.scope().clear());
        assertThat(snapshot.plannedStartAt()).isNull();
    }

    @Test
    void rejectsValuesOutsideTheAgreedDecimalPrecisionWithoutRounding() {
        assertThrows(IllegalArgumentException.class, () -> line("1.0001", "40.00"));
        assertThrows(IllegalArgumentException.class, () -> line("1", "40.001"));
        assertThrows(IllegalArgumentException.class, () -> line("0", "40.00"));
        assertThrows(IllegalArgumentException.class, () -> command(List.of(line("1", "40")), "40.001"));
        assertThrows(IllegalArgumentException.class, () -> command(List.of(), "0.00"));
    }

    private ScopeLine line(String quantity, String price) {
        return new ScopeLine(ScopeLine.Type.LABOR, "Installation", new BigDecimal(quantity),
                "h", new BigDecimal(price));
    }

    private CreateFromAcceptedOffer command(List<ScopeLine> scope, String total) {
        return new CreateFromAcceptedOffer(UUID.randomUUID(), UUID.randomUUID(),
                new CustomerSnapshot("Customer", null, "123456789"), UUID.randomUUID(),
                new AddressSnapshot("Street 1", "00-001", "Warsaw"), scope,
                new BigDecimal(total), null, Instant.parse("2026-10-22T10:00:00Z"), UUID.randomUUID());
    }
}
