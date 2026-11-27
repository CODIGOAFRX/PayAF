package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Respuesta de una pasarela a cualquiera de sus operaciones, ya traducida por el adaptador.
 * <p>
 * Reutiliza {@link AttemptOutcome} del dominio: el adaptador ya clasifica la respuesta en
 * los cuatro casos que el negocio distingue, y la aplicación no conoce los códigos propios
 * de cada PSP más que como texto informativo ({@code code}).
 * <p>
 * Una respuesta APPROVED tiene que traer la referencia de la operación en la PSP: sin ella
 * no se podría capturar, reembolsar ni anular después. Construir una APPROVED sin
 * referencia es un error de programación del adaptador y falla al instante.
 *
 * @param outcome          resultado clasificado
 * @param gatewayReference referencia de la operación en la PSP; obligatoria si es APPROVED
 * @param code             código de la PSP, si lo dio (p. ej. {@code "insufficient_funds"})
 * @param latency          lo que tardó la llamada; no negativa
 */
public record GatewayResponse(AttemptOutcome outcome, Optional<String> gatewayReference, Optional<String> code,
                              Duration latency) {

    public GatewayResponse {
        Objects.requireNonNull(outcome, "el resultado no puede ser nulo");
        Objects.requireNonNull(gatewayReference, "la referencia opcional no puede ser nula: usa Optional.empty()");
        Objects.requireNonNull(code, "el código opcional no puede ser nulo: usa Optional.empty()");
        Objects.requireNonNull(latency, "la latencia no puede ser nula");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("La latencia no puede ser negativa: " + latency);
        }
        if (outcome == AttemptOutcome.APPROVED && gatewayReference.filter(r -> !r.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("Una respuesta APPROVED debe traer la referencia de la pasarela");
        }
    }

    public static GatewayResponse approved(String gatewayReference, Duration latency) {
        return new GatewayResponse(AttemptOutcome.APPROVED, Optional.of(gatewayReference), Optional.empty(), latency);
    }

    public static GatewayResponse declined(String code, Duration latency) {
        return new GatewayResponse(AttemptOutcome.DECLINED, Optional.empty(), Optional.ofNullable(code), latency);
    }

    public static GatewayResponse error(String code, Duration latency) {
        return new GatewayResponse(AttemptOutcome.ERROR, Optional.empty(), Optional.ofNullable(code), latency);
    }

    public static GatewayResponse timeout(Duration latency) {
        return new GatewayResponse(AttemptOutcome.TIMEOUT, Optional.empty(), Optional.empty(), latency);
    }

    public boolean isApproved() {
        return outcome == AttemptOutcome.APPROVED;
    }
}
