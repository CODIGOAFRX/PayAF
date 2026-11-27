package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.OPEN_DURATION;
import static dev.pedrogomez.payaf.application.service.Harness.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.GatewayHealthView;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lo inesperado durante el enrutado no deja pagos en PENDING ni claves en curso para siempre. */
class RoutingSafetyNetTest {

    private static final GatewayId FIRST = GatewayId.of("first");
    private static final GatewayId SECOND = GatewayId.of("second");

    private Harness h = new Harness(CircuitBreakerConfig.of(2, OPEN_DURATION), false);

    @AfterEach
    void discipline() {
        h.assertTransactionDiscipline();
    }

    @Test
    @DisplayName("una excepción comprobada lanzada a escondidas por el adaptador es un ERROR y hay failover")
    void sneakyCheckedExceptionIsAnError() {
        h.catalog.register(Harness.profile(FIRST, 100), new FunctionalGateway(FIRST, h.transactions, a -> {
            throw FunctionalGateway.sneakyThrow(new IOException("connection reset"));
        }));
        h.catalog.register(Harness.profile(SECOND, 200),
                new FunctionalGateway(SECOND, h.transactions, a -> GatewayResponse.approved("ok_1", LATENCY)));

        AuthorizationResult result = h.authorize("k1");

        assertThat(result.attempts()).extracting(GatewayAttempt::gateway, GatewayAttempt::code)
                .containsExactly(tuple(FIRST, Optional.of(ServiceSupport.ADAPTER_EXCEPTION)),
                        tuple(SECOND, Optional.empty()));
        assertThat(result.payment().authorizedBy()).contains(SECOND);
    }

    @Test
    @DisplayName("un desbordamiento en la política deja el pago FAILED con ROUTING_ERROR y la clave completada")
    void overflowInRoutingPolicyFailsThePayment() {
        FunctionalGateway gateway = new FunctionalGateway(FIRST, h.transactions, a -> {
            throw new AssertionError("no se debería llegar a llamar a la pasarela");
        });
        // 100 % del importe más 1 céntimo fijo: con Long.MAX_VALUE la comisión desborda.
        h.catalog.register(new GatewayProfile(FIRST, Set.of(Currency.EUR), Set.of(), 10_000,
                Map.of(Currency.EUR, 1L), true), gateway);
        PaymentRequest huge = Harness.request("k-huge", Long.MAX_VALUE);

        AuthorizationResult result = h.authorize(huge);

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.ROUTING_ERROR);
        assertThat(result.replayed()).isFalse();
        assertThat(h.idempotency.isCompleted(huge.key())).isTrue();
        assertThat(h.payments.stored(result.payment().id()).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(gateway.authorizeCalls()).isZero();

        // Reintento del comercio: repetición idempotente, no IDEMPOTENCY_IN_PROGRESS.
        AuthorizationResult retry = h.authorize(huge);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.payment().id()).isEqualTo(result.payment().id());
    }

    @Test
    @DisplayName("un Error de la JVM no se disfraza: se propaga")
    void jvmErrorPropagates() {
        h.catalog.register(Harness.profile(FIRST, 100), new FunctionalGateway(FIRST, h.transactions, a -> {
            throw new OutOfMemoryError("simulado");
        }));

        assertThatThrownBy(() -> h.authorize("k1")).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("el panel no cuenta en HALF_OPEN una prueba caducada, sin tocar el breaker real")
    void healthIgnoresExpiredTrial() {
        h = new Harness();
        CircuitBreaker breaker = h.breakers.get(STRIPE);
        Instant t0 = h.clock.instant();
        breaker.recordFailure(t0);
        breaker.recordFailure(t0);
        Instant halfOpenAt = t0.plus(OPEN_DURATION);
        assertThat(breaker.tryAcquirePermission(halfOpenAt)).isTrue();

        GatewayHealthView fresh = stripeHealth(halfOpenAt);
        GatewayHealthView expired = stripeHealth(halfOpenAt.plus(OPEN_DURATION));

        assertThat(fresh.circuit().state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(fresh.circuit().trialsInFlight()).isEqualTo(1);
        assertThat(expired.circuit().state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(expired.circuit().trialsInFlight()).isZero();
        assertThat(breaker.snapshot().trialsInFlight()).isEqualTo(1);
        assertThat(breaker.pullEvents()).noneMatch(e -> e.occurredAt().isAfter(halfOpenAt));
    }

    private GatewayHealthView stripeHealth(Instant now) {
        return h.health.health(now).stream().filter(v -> v.profile().id().equals(STRIPE)).findFirst().orElseThrow();
    }
}
