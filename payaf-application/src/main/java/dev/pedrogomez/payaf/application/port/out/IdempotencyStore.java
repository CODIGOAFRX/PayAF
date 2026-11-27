package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.RequestFingerprint;
import java.time.Instant;
import java.util.Objects;

/**
 * Puerto de salida (driven): registro de claves de idempotencia.
 * <p>
 * La idempotencia es una garantía de negocio (no cobrar dos veces la misma orden), pero
 * hacerla cumplir con varias peticiones a la vez es un problema de almacenamiento: hace
 * falta una operación atómica de «reservar si no existe». Por eso la aplicación define aquí
 * lo que necesita y el adaptador lo resuelve con lo que tenga (una clave única en
 * PostgreSQL, un {@code ConcurrentHashMap} en memoria).
 * <p>
 * Ciclo de vida de una clave: no existe → {@link #tryReserve} la deja reservada (en curso)
 * para un pago → {@link #complete} la marca como completada cuando el pago ha terminado de
 * enrutarse.
 * <p>
 * <strong>Contrato de atomicidad.</strong> {@link #tryReserve} DEBE ser atómico frente a la
 * concurrencia: si dos peticiones con la misma clave lo llaman a la vez, exactamente una
 * recibe {@link Reservation.Reserved} y la otra ve la reserva de la primera. Si esto no se
 * cumple, dos pagos con la misma clave saldrían hacia las pasarelas. Lo garantizan los
 * adaptadores; la aplicación no puede hacerlo por ellos.
 * <p>
 * Se llama dentro de una transacción abierta con {@link TransactionRunner}.
 */
public interface IdempotencyStore {

    /**
     * Intenta reservar la clave para el pago {@code candidate}.
     *
     * @param key         clave de idempotencia que envía el comercio
     * @param fingerprint huella del contenido de la orden
     * @param candidate   identificador que tendrá el pago si la reserva sale bien
     * @param now         instante de la reserva
     * @return {@link Reservation.Reserved} si la clave era nueva, o lo que ya había guardado
     */
    Reservation tryReserve(IdempotencyKey key, RequestFingerprint fingerprint, PaymentId candidate, Instant now);

    /** Marca como completada una clave reservada antes para ese pago. */
    void complete(IdempotencyKey key, PaymentId paymentId);

    /** Resultado de {@link #tryReserve}. Es sellado para que el caso de uso trate todos los casos. */
    sealed interface Reservation permits Reservation.Reserved, Reservation.AlreadyCompleted, Reservation.InProgress {

        /** La clave era nueva y ahora queda reservada para el pago candidato. */
        record Reserved() implements Reservation {
        }

        /** La clave ya se usó y su pago terminó de enrutarse. */
        record AlreadyCompleted(RequestFingerprint fingerprint, PaymentId paymentId) implements Reservation {

            public AlreadyCompleted {
                Objects.requireNonNull(fingerprint);
                Objects.requireNonNull(paymentId);
            }
        }

        /** La clave está reservada por otra petición que aún no ha terminado. */
        record InProgress(RequestFingerprint fingerprint, PaymentId paymentId) implements Reservation {

            public InProgress {
                Objects.requireNonNull(fingerprint);
                Objects.requireNonNull(paymentId);
            }
        }
    }
}
