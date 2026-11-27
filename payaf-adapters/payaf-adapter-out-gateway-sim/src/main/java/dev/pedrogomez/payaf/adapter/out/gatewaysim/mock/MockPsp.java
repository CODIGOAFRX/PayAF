package dev.pedrogomez.payaf.adapter.out.gatewaysim.mock;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.DeterministicDecline;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PSP de pruebas, la red de seguridad del catálogo: admite cualquier divisa y país, y es cara
 * (900 pb) para que solo se use cuando las demás no pueden.
 * <p>
 * Su «idioma» es el más sencillo posible: cada operación devuelve un {@link MockReply} con
 * {@code OK} o {@code KO} y un motivo. Las referencias son {@code mock_} y un contador
 * ({@code mock_000001}). Rechaza con {@code KO}/{@code mock_decline_51} los importes terminados
 * en 51 y con {@code KO}/{@code UNKNOWN_REFERENCE}, {@code INVALID_STATE}... las operaciones que
 * su libro no admite. Es segura para hilos.
 */
public final class MockPsp {

    public static final String OK = "OK";
    public static final String KO = "KO";
    public static final String DECLINE_REASON = "mock_decline_51";

    /** Respuesta de la PSP de pruebas. {@code reason} solo va con {@code KO}. */
    public record MockReply(String status, String reference, String reason) {
    }

    private final SimulatedLedger ledger = new SimulatedLedger();
    private final AtomicLong sequence = new AtomicLong();

    public MockReply authorize(long amountMinor, String currency) {
        Objects.requireNonNull(currency);
        if (amountMinor <= 0) {
            return new MockReply(KO, null, "INVALID_AMOUNT");
        }
        if (DeterministicDecline.declines(amountMinor)) {
            return new MockReply(KO, null, DECLINE_REASON);
        }
        String reference = "mock_%06d".formatted(sequence.incrementAndGet());
        ledger.open(reference, amountMinor, currency);
        return new MockReply(OK, reference, null);
    }

    public MockReply capture(String reference, long amountMinor, String currency) {
        return onLedger(reference, () -> ledger.capture(reference, amountMinor, currency));
    }

    /** La referencia de un reembolso es {@code <referencia>-r<n>}. */
    public MockReply refund(String reference, long amountMinor, String currency) {
        try {
            SimulatedLedger.Entry entry = ledger.refund(reference, amountMinor, currency);
            return new MockReply(OK, reference + "-r" + entry.refundCount(), null);
        } catch (SimulatedLedger.RejectedException e) {
            return new MockReply(KO, null, e.rejection().name());
        }
    }

    public MockReply cancel(String reference) {
        return onLedger(reference, () -> ledger.cancel(reference));
    }

    public SimulatedLedger ledger() {
        return ledger;
    }

    private static MockReply onLedger(String reference, java.util.function.Supplier<SimulatedLedger.Entry> op) {
        try {
            op.get();
            return new MockReply(OK, reference, null);
        } catch (SimulatedLedger.RejectedException e) {
            return new MockReply(KO, null, e.rejection().name());
        }
    }
}
