package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Política de enrutado: dada una orden de pago, decide qué pasarelas pueden atenderla y
 * en qué orden conviene probarlas.
 * <p>
 * Es un servicio de dominio porque la regla no pertenece a ninguna entidad concreta: mira
 * a la vez la orden, los perfiles de todas las pasarelas y sus circuit breakers. No tiene
 * estado propio, así que una única instancia sirve para todo el sistema.
 * <p>
 * Reglas:
 * <ol>
 *   <li>Descarta, anotando el motivo ({@link RejectionReason}), las pasarelas deshabilitadas,
 *       las que no admiten la divisa, las que no operan en el país y las que tienen el
 *       circuito cerrado al tráfico ({@link CircuitBreaker#allowsRequest} = false). Si una
 *       pasarela no tiene breaker en el mapa, se considera CLOSED.</li>
 *   <li>Ordena las candidatas por comisión estimada, de menor a mayor.</li>
 *   <li>A igual comisión, primero la que tiene el circuito CLOSED y después la que está en
 *       HALF_OPEN (que aún está demostrando que se ha recuperado).</li>
 *   <li>Si todo empata, por {@link GatewayId}, para que la decisión sea determinista.</li>
 *   <li>Los descartes se devuelven ordenados por {@link GatewayId}, no en el orden de
 *       entrada: el orden de los perfiles no influye en la decisión.</li>
 * </ol>
 * <p>
 * <strong>Solo decide, no actúa.</strong> No llama a ninguna pasarela ni reserva la petición
 * de prueba de los breakers en HALF_OPEN. El failover (probar la primera candidata y, si
 * falla, la siguiente) lo orquesta el caso de uso de la aplicación, que es quien habla con
 * las pasarelas a través de un puerto de salida. El único efecto sobre los breakers es el
 * paso perezoso de OPEN a HALF_OPEN cuando ha vencido su tiempo de apertura.
 */
public final class RoutingPolicy {

    private static final Comparator<RoutingCandidate> PREFERENCE = Comparator
            .comparingLong((RoutingCandidate c) -> c.estimatedFee().amountMinor())
            .thenComparing(c -> c.circuitState() == CircuitState.CLOSED ? 0 : 1)
            .thenComparing(RoutingCandidate::gateway);

    /**
     * @param request  orden de pago
     * @param profiles perfiles de las pasarelas configuradas; sin identificadores repetidos
     * @param breakers circuit breaker de cada pasarela
     * @param now      instante de la decisión
     * @throws InvalidValueException si hay dos perfiles con el mismo identificador
     */
    public RoutingDecision decide(PaymentRequest request, List<GatewayProfile> profiles,
                                  Map<GatewayId, CircuitBreaker> breakers, Instant now) {
        Objects.requireNonNull(request, "la orden de pago no puede ser nula");
        Objects.requireNonNull(profiles, "los perfiles no pueden ser nulos");
        Objects.requireNonNull(breakers, "los breakers no pueden ser nulos");
        Objects.requireNonNull(now, "el instante no puede ser nulo");

        List<RoutingCandidate> candidates = new ArrayList<>();
        List<RoutingRejection> rejections = new ArrayList<>();
        Set<GatewayId> seen = new HashSet<>();
        Money amount = request.amount();

        for (GatewayProfile profile : profiles) {
            Objects.requireNonNull(profile, "no puede haber perfiles nulos");
            if (!seen.add(profile.id())) {
                throw new InvalidValueException("La pasarela " + profile.id() + " está configurada dos veces");
            }
            RejectionReason reason = rejectionReason(profile, request, breakers.get(profile.id()), now);
            if (reason != null) {
                rejections.add(new RoutingRejection(profile.id(), reason));
                continue;
            }
            CircuitBreaker breaker = breakers.get(profile.id());
            CircuitState circuitState = breaker == null ? CircuitState.CLOSED : breaker.state();
            candidates.add(new RoutingCandidate(profile.id(), profile.estimatedFee(amount), circuitState));
        }

        candidates.sort(PREFERENCE);
        rejections.sort(Comparator.comparing(RoutingRejection::gateway));
        return new RoutingDecision(candidates, rejections);
    }

    /** El primer motivo de descarte, o {@code null} si la pasarela es apta. */
    private static RejectionReason rejectionReason(GatewayProfile profile, PaymentRequest request,
                                                   CircuitBreaker breaker, Instant now) {
        if (!profile.enabled()) {
            return RejectionReason.DISABLED;
        }
        if (!profile.supports(request.amount().currency())) {
            return RejectionReason.CURRENCY_NOT_SUPPORTED;
        }
        if (!profile.supports(request.country())) {
            return RejectionReason.COUNTRY_NOT_SUPPORTED;
        }
        if (breaker != null && !breaker.allowsRequest(now)) {
            return RejectionReason.CIRCUIT_OPEN;
        }
        return null;
    }
}
