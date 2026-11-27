package dev.pedrogomez.payaf.domain.payment;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Un intento de autorizar el pago en una pasarela, con su resultado.
 * <p>
 * Es dominio porque el historial de intentos forma parte del pago: explica qué pasarelas
 * se probaron, en qué orden, cuánto tardaron y por qué falló cada una. Es lo que permite
 * auditar el failover y lo que el panel enseñará para explicar dónde acabó el pago.
 *
 * @param gateway          pasarela a la que se pidió la autorización
 * @param outcome          resultado del intento
 * @param latency          lo que tardó en responder (o en agotar el tiempo); no puede ser negativa
 * @param code             código de respuesta de la pasarela, si lo dio (p. ej. {@code "insufficient_funds"})
 * @param gatewayReference referencia de la operación en la pasarela, si la dio
 * @param at               cuándo terminó el intento
 */
public record GatewayAttempt(
        GatewayId gateway,
        AttemptOutcome outcome,
        Duration latency,
        Optional<String> code,
        Optional<String> gatewayReference,
        Instant at) {

    public GatewayAttempt {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        Objects.requireNonNull(outcome, "el resultado no puede ser nulo");
        Objects.requireNonNull(latency, "la latencia no puede ser nula");
        Objects.requireNonNull(code, "el código opcional no puede ser nulo: usa Optional.empty()");
        Objects.requireNonNull(gatewayReference, "la referencia opcional no puede ser nula: usa Optional.empty()");
        Objects.requireNonNull(at, "el instante no puede ser nulo");
        if (latency.isNegative()) {
            throw new InvalidValueException("La latencia no puede ser negativa: " + latency);
        }
    }
}
