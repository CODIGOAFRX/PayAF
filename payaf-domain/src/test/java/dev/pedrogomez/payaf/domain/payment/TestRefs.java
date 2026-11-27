package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ayudas para los tests escritos antes de que los reembolsos llevaran referencia: cada
 * llamada a {@link #next()} da una referencia nueva, así un reembolso de esos tests nunca
 * se confunde con una repetición idempotente y lo que comprueban sigue siendo lo mismo.
 */
final class TestRefs {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private TestRefs() {
    }

    /** Una referencia de reembolso que no se ha usado nunca. */
    static String next() {
        return "re_test_" + SEQUENCE.incrementAndGet();
    }

    /**
     * Reembolsos coherentes con un total, para rehidratar pagos: ninguno si el total no es
     * positivo (o es nulo) y, si lo es, uno solo por ese importe.
     */
    static List<Refund> refundsFor(Money refunded) {
        if (refunded == null || !refunded.isPositive()) {
            return List.of();
        }
        return List.of(new Refund(next(), refunded, Instant.EPOCH));
    }
}
