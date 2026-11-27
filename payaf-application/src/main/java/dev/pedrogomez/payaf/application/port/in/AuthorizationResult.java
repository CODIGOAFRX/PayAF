package dev.pedrogomez.payaf.application.port.in;

import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.routing.RoutingDecision;
import java.util.List;
import java.util.Objects;

/**
 * Resultado de autorizar un pago.
 *
 * @param payment  el pago tal como ha quedado (AUTHORIZED o FAILED)
 * @param decision la decisión de enrutado que se tomó en esta petición. En una repetición
 *                 idempotente ({@code replayed = true}) es una decisión <strong>vacía</strong>
 *                 (sin candidatas ni descartes): la decisión original no se guarda y
 *                 recalcularla ahora daría una respuesta distinta de la que se usó, con los
 *                 breakers de ahora. La historia real del enrutado está en {@code attempts}.
 * @param attempts intentos del pago en las pasarelas, en orden
 * @param replayed si es la respuesta guardada de una orden anterior con la misma clave
 */
public record AuthorizationResult(PaymentView payment, RoutingDecision decision, List<GatewayAttempt> attempts,
                                  boolean replayed) {

    public AuthorizationResult {
        Objects.requireNonNull(payment);
        Objects.requireNonNull(decision);
        attempts = List.copyOf(Objects.requireNonNull(attempts));
    }
}
