package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Libro de operaciones de una PSP simulada: qué autorizaciones tiene, si se han capturado o
 * anulado y cuánto se ha reembolsado de cada una. Cada PSP tiene el suyo, en memoria.
 * <p>
 * Es lo que hace creíble al simulador: una PSP real no captura una autorización que no existe,
 * no captura dos veces, no anula lo capturado ni reembolsa más de lo cobrado. Aquí tampoco, y
 * cada cliente falso traduce el {@link Rejection} a los errores nativos de su PSP.
 * <p>
 * Es seguro para hilos: cada transición se hace dentro de {@link ConcurrentMap#compute}, así que
 * dos capturas simultáneas de la misma referencia no pueden salir bien las dos. Si la
 * transición no es válida se lanza {@link RejectedException} desde dentro de {@code compute} y la
 * entrada no cambia.
 * <p>
 * Los importes van en unidades menores y la divisa como texto, tal como los guarda cada PSP: el
 * libro es del lado de la PSP y no usa los tipos del dominio.
 */
public final class SimulatedLedger {

    /** Estado de una autorización en la PSP. */
    public enum State { AUTHORIZED, CAPTURED, CANCELED }

    /** Por qué la PSP no admite una operación. */
    public enum Rejection {
        /** No hay ninguna autorización con esa referencia. */
        UNKNOWN_REFERENCE,
        /** La autorización no está en un estado que admita la operación (capturar dos veces, por ejemplo). */
        INVALID_STATE,
        /** El importe o la divisa no cuadran con la autorización. */
        AMOUNT_MISMATCH,
        /** El reembolso superaría lo capturado. */
        REFUND_EXCEEDS_CAPTURED
    }

    /**
     * Una autorización en el libro.
     *
     * @param refundedMinor total reembolsado
     * @param refundCount   cuántos reembolsos se han hecho
     */
    public record Entry(String reference, long amountMinor, String currency, State state, long refundedMinor,
                        int refundCount) {
    }

    /** La PSP rechaza la operación; el libro no ha cambiado. */
    public static final class RejectedException extends RuntimeException {

        private final Rejection rejection;

        RejectedException(Rejection rejection, String reference) {
            super(rejection + ": " + reference);
            this.rejection = rejection;
        }

        public Rejection rejection() {
            return rejection;
        }
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Apunta una autorización nueva.
     *
     * @throws IllegalStateException si la referencia ya existe (la PSP genera referencias únicas)
     */
    public Entry open(String reference, long amountMinor, String currency) {
        Objects.requireNonNull(reference);
        Objects.requireNonNull(currency);
        Entry entry = new Entry(reference, amountMinor, currency, State.AUTHORIZED, 0, 0);
        if (entries.putIfAbsent(reference, entry) != null) {
            throw new IllegalStateException("Referencia repetida en el libro: " + reference);
        }
        return entry;
    }

    /** Captura el total de la autorización. Exige el mismo importe y divisa. */
    public Entry capture(String reference, long amountMinor, String currency) {
        return transition(reference, entry -> {
            if (entry.state() != State.AUTHORIZED) {
                throw new RejectedException(Rejection.INVALID_STATE, reference);
            }
            if (entry.amountMinor() != amountMinor || !entry.currency().equals(currency)) {
                throw new RejectedException(Rejection.AMOUNT_MISMATCH, reference);
            }
            return new Entry(reference, entry.amountMinor(), entry.currency(), State.CAPTURED, 0, 0);
        });
    }

    /** Reembolsa parte o todo lo capturado. */
    public Entry refund(String reference, long amountMinor, String currency) {
        return transition(reference, entry -> {
            if (entry.state() != State.CAPTURED) {
                throw new RejectedException(Rejection.INVALID_STATE, reference);
            }
            if (amountMinor <= 0 || !entry.currency().equals(currency)) {
                throw new RejectedException(Rejection.AMOUNT_MISMATCH, reference);
            }
            if (amountMinor > entry.amountMinor() - entry.refundedMinor()) {
                throw new RejectedException(Rejection.REFUND_EXCEEDS_CAPTURED, reference);
            }
            return new Entry(reference, entry.amountMinor(), entry.currency(), State.CAPTURED,
                    entry.refundedMinor() + amountMinor, entry.refundCount() + 1);
        });
    }

    /** Anula una autorización no capturada. */
    public Entry cancel(String reference) {
        return transition(reference, entry -> {
            if (entry.state() != State.AUTHORIZED) {
                throw new RejectedException(Rejection.INVALID_STATE, reference);
            }
            return new Entry(reference, entry.amountMinor(), entry.currency(), State.CANCELED, 0, 0);
        });
    }

    public Optional<Entry> find(String reference) {
        return Optional.ofNullable(entries.get(reference));
    }

    public int size() {
        return entries.size();
    }

    private Entry transition(String reference, java.util.function.UnaryOperator<Entry> change) {
        Objects.requireNonNull(reference);
        Entry result = entries.compute(reference, (ref, entry) -> {
            if (entry == null) {
                throw new RejectedException(Rejection.UNKNOWN_REFERENCE, reference);
            }
            return change.apply(entry);
        });
        return Objects.requireNonNull(result);
    }
}
