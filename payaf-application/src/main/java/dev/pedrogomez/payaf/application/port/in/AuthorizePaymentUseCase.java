package dev.pedrogomez.payaf.application.port.in;

/**
 * Puerto de entrada (driving): autorizar una orden de pago, eligiendo la pasarela y
 * haciendo failover si falla.
 * <p>
 * Es la operación principal de PayAF. Está en la aplicación porque es lo que el sistema
 * ofrece al mundo; los adaptadores de entrada (REST, CLI) la invocan sin saber cómo se
 * implementa. Es idempotente por la clave de la orden: repetir la misma orden devuelve el
 * mismo pago sin volver a llamar a ninguna pasarela.
 *
 * @see dev.pedrogomez.payaf.application.service.AuthorizePaymentService
 */
public interface AuthorizePaymentUseCase {

    /**
     * @throws dev.pedrogomez.payaf.application.exception.ApplicationException
     *         IDEMPOTENCY_CONFLICT si la clave ya se usó con otra orden, o IDEMPOTENCY_IN_PROGRESS
     *         si la misma orden se está procesando ahora mismo en otra petición
     */
    AuthorizationResult authorize(AuthorizePaymentCommand command);
}
