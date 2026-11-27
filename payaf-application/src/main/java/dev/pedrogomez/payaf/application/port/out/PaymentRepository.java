package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida (driven): dónde se guardan los pagos.
 * <p>
 * Está en la aplicación, y no en un adaptador, porque es la aplicación quien decide
 * <em>qué</em> necesita guardar y buscar; el adaptador (JPA, memoria) solo decide
 * <em>cómo</em>. Así el caso de uso se prueba con un repositorio en memoria y se cambia de
 * base de datos sin tocarlo. El repositorio trabaja con el agregado entero: lo reconstruye
 * con {@link Payment#rehydrate} y no expone filas ni entidades JPA.
 * <p>
 * Contrato: se llama siempre dentro de una transacción abierta con {@link TransactionRunner}.
 */
public interface PaymentRepository {

    /**
     * Inserta o actualiza el pago con todos sus intentos y reembolsos. No publica sus eventos.
     * <p>
     * <strong>Control optimista de versión.</strong> {@link Payment#version()} es la versión
     * con la que se cargó el pago (0 si es nuevo y nunca se ha guardado). El adaptador debe:
     * <ol>
     *   <li>Comparar esa versión con la guardada (0 si el pago no está guardado), por ejemplo con
     *       una columna {@code version} y un {@code UPDATE ... WHERE version = ?}.</li>
     *   <li>Si no coinciden (otra petición lo ha guardado entretanto, o se intenta insertar dos
     *       veces el mismo pago nuevo), no escribir nada y lanzar
     *       {@link dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException}
     *       (código {@code CONCURRENT_MODIFICATION}), que deshace la transacción.</li>
     *   <li>Si coinciden, guardar con la versión incrementada y llamar a
     *       {@link Payment#markPersisted(long)} con ella, para que un segundo {@code save} de la
     *       misma instancia parta de lo que acaba de quedar guardado.</li>
     * </ol>
     * Si la transacción se deshace después del {@code save}, la instancia queda con una versión
     * que no está guardada: hay que descartarla y volver a cargar el pago.
     */
    void save(Payment payment);

    Optional<Payment> findById(PaymentId id);

    /**
     * Busca el pago por la referencia que le dio la pasarela que lo autorizó. Es como
     * llegan las notificaciones de los PSP, que no conocen nuestro {@link PaymentId}.
     */
    Optional<Payment> findByGatewayReference(GatewayId gateway, String gatewayReference);

    /** Los últimos pagos, del más reciente al más antiguo, como mucho {@code limit}. */
    List<Payment> findRecent(int limit);

    /**
     * Pagos que siguen en PENDING y no han cambiado desde antes de {@code cutoff}
     * ({@link Payment#updatedAt()} estrictamente anterior), del más antiguo al más reciente y
     * como mucho {@code limit}. Es lo que necesita el reaper de pagos huérfanos.
     * <p>
     * Ojo: mientras un pago prueba pasarelas, sus intentos NO se guardan uno a uno; el pago solo
     * se guarda al crearse (transacción 1) y al cerrarse (transacción 2) en
     * {@code AuthorizePaymentService}. Su {@code updatedAt} guardado es, por tanto, el de la
     * creación durante todo el enrutado. El reaper no distingue un pago en vuelo de uno huérfano
     * por este campo: confía en que su umbral sea mucho mayor que lo que puede durar un enrutado
     * (del orden de nº de pasarelas × timeout), y si aun así coinciden, la versión optimista
     * decide quién gana (ver {@code ReaperScheduler} en bootstrap).
     */
    List<Payment> findPendingOlderThan(Instant cutoff, int limit);
}
