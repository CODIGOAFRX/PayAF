package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
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
     * <strong>Control optimista de versión.</strong> El adaptador debe comprobar que el pago
     * que se guarda parte de la última versión guardada (por ejemplo, con una columna
     * {@code version} y un {@code UPDATE ... WHERE version = ?}). Si otra petición lo ha
     * guardado entretanto, no lo sobrescribe y lanza
     * {@link dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException}
     * (código {@code CONCURRENT_MODIFICATION}), que deshace la transacción.
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
}
