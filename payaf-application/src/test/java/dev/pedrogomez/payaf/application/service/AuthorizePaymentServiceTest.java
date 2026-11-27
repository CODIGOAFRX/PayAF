package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.AUTHORIZE;
import static dev.pedrogomez.payaf.application.service.Harness.ADYEN;
import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.routing.RejectionReason;
import dev.pedrogomez.payaf.domain.routing.RoutingCandidate;
import dev.pedrogomez.payaf.domain.routing.RoutingRejection;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizePaymentServiceTest {

    private final Harness h = new Harness();

    @AfterEach
    void noGatewayCallInsideATransaction() {
        h.assertTransactionDiscipline();
    }

    @Test
    @DisplayName("autorización feliz: la pasarela más barata aprueba a la primera")
    void happyAuthorization() {
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));

        AuthorizationResult result = h.authorize("k1");

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.payment().authorizedBy()).contains(STRIPE);
        assertThat(result.payment().gatewayReference()).contains("ch_1");
        assertThat(result.decision().candidates()).extracting(RoutingCandidate::gateway).containsExactly(STRIPE, ADYEN);
        assertThat(result.attempts()).singleElement()
                .satisfies(a -> assertThat(a.outcome()).isEqualTo(AttemptOutcome.APPROVED));
        assertThat(h.adyen.calls()).isEmpty();

        assertThat(h.payments.stored(result.payment().id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(h.idempotency.isCompleted(Harness.request("k1", 10_000).key())).isTrue();
        assertThat(h.events.published(PaymentCreated.class)).hasSize(1);
        assertThat(h.events.published(PaymentAuthorized.class)).hasSize(1);
        assertThat(h.transactions.transactionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("failover: ERROR en Stripe y Adyen aprueba")
    void failoverFromErrorToNextGateway() {
        h.stripe.on(AUTHORIZE, GatewayResponse.error("http_503", LATENCY));
        h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_9", LATENCY));

        AuthorizationResult result = h.authorize("k1");

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.payment().authorizedBy()).contains(ADYEN);
        assertThat(result.attempts()).extracting(GatewayAttempt::gateway, GatewayAttempt::outcome)
                .containsExactly(
                        tuple(STRIPE, AttemptOutcome.ERROR),
                        tuple(ADYEN, AttemptOutcome.APPROVED));
        assertThat(result.attempts().get(0).code()).contains("http_503");
        // El tiempo de cada paso es fresco y monótono.
        assertThat(result.attempts().get(1).at()).isAfter(result.attempts().get(0).at());
    }

    @Test
    @DisplayName("un DECLINED no hace failover: el pago falla y Adyen ni se entera")
    void declinedDoesNotFailover() {
        h.stripe.on(AUTHORIZE, GatewayResponse.declined("insufficient_funds", LATENCY));

        AuthorizationResult result = h.authorize("k1");

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains("DECLINED: insufficient_funds");
        assertThat(h.adyen.calls()).isEmpty();
        // Un rechazo no es un fallo técnico: no cuenta para el breaker.
        assertThat(h.breakers.get(STRIPE).snapshot().consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("todas las pasarelas caídas: FAILED con NO_GATEWAY_AVAILABLE")
    void allGatewaysDown() {
        h.stripe.on(AUTHORIZE, GatewayResponse.timeout(Duration.ofSeconds(5)));
        h.adyen.on(AUTHORIZE, GatewayResponse.error("http_500", LATENCY));

        AuthorizationResult result = h.authorize("k1");

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.NO_GATEWAY_AVAILABLE);
        assertThat(result.attempts()).hasSize(2);
        assertThat(h.idempotency.isCompleted(Harness.request("k1", 10_000).key())).isTrue();
    }

    @Test
    @DisplayName("si el adaptador lanza una excepción, cuenta como ERROR y se hace failover")
    void adapterExceptionIsTreatedAsError() {
        // Sin guion, ScriptedGateway lanza AssertionError, que no es RuntimeException; aquí
        // se simula un adaptador que incumple el contrato con una RuntimeException.
        h.catalog.register(new GatewayProfile(
                GatewayId.of("broken"),
                Set.of(Currency.EUR), Set.of(), 0,
                Map.of(), true), new BrokenGateway());
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));

        AuthorizationResult result = h.authorize("k1");

        assertThat(result.attempts()).extracting(GatewayAttempt::outcome)
                .containsExactly(AttemptOutcome.ERROR, AttemptOutcome.APPROVED);
        assertThat(result.attempts().get(0).code()).contains(ServiceSupport.ADAPTER_EXCEPTION);
        assertThat(result.payment().authorizedBy()).contains(STRIPE);
    }

    @Test
    @DisplayName("replay idempotente: devuelve el mismo pago y no llama a ninguna pasarela")
    void idempotentReplay() {
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));
        AuthorizationResult first = h.authorize("k1");

        AuthorizationResult again = h.authorize("k1");

        assertThat(again.replayed()).isTrue();
        assertThat(again.payment()).isEqualTo(first.payment());
        assertThat(again.attempts()).isEqualTo(first.attempts());
        assertThat(again.decision().candidates()).isEmpty();
        assertThat(again.decision().rejections()).isEmpty();
        assertThat(h.stripe.calls(AUTHORIZE)).isEqualTo(1);
        assertThat(h.payments.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("misma clave con otra orden: IDEMPOTENCY_CONFLICT")
    void fingerprintConflict() {
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));
        h.authorize("k1");

        assertThatThrownBy(() -> h.authorizeService.authorize(
                new AuthorizePaymentCommand(Harness.request("k1", 99_999))))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.IDEMPOTENCY_CONFLICT));
        assertThat(h.stripe.calls(AUTHORIZE)).isEqualTo(1);
    }

    @Test
    @DisplayName("misma orden mientras otra petición la procesa: IDEMPOTENCY_IN_PROGRESS")
    void inProgress() {
        var request = Harness.request("k1", 10_000);
        h.transactions.inTransaction(() -> h.idempotency.tryReserve(request.key(), request.fingerprint(),
                PaymentId.of(new UUID(9, 9)), h.clock.instant()));

        assertThatThrownBy(() -> h.authorize("k1"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.IDEMPOTENCY_IN_PROGRESS));
        assertThat(h.stripe.calls()).isEmpty();
    }

    @Test
    @DisplayName("el breaker se abre tras N fallos y la siguiente petición ni intenta esa pasarela")
    void breakerOpensAfterThresholdAndIsSkipped() {
        for (int i = 0; i < Harness.FAILURE_THRESHOLD; i++) {
            h.stripe.on(AUTHORIZE, GatewayResponse.error("http_503", LATENCY));
            h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_" + i, LATENCY));
            assertThat(h.authorize("k" + i).payment().authorizedBy()).contains(ADYEN);
        }
        assertThat(h.breakers.get(STRIPE).state()).isEqualTo(CircuitState.OPEN);
        assertThat(h.events.published(CircuitOpened.class)).singleElement()
                .satisfies(e -> assertThat(e.gateway()).isEqualTo(STRIPE));

        h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_next", LATENCY));
        AuthorizationResult next = h.authorize("k-next");

        assertThat(h.stripe.calls(AUTHORIZE)).isEqualTo(Harness.FAILURE_THRESHOLD);
        assertThat(next.attempts()).extracting(GatewayAttempt::gateway).containsExactly(ADYEN);
        assertThat(next.decision().rejections())
                .containsExactly(new RoutingRejection(STRIPE, RejectionReason.CIRCUIT_OPEN));
    }

    @Test
    @DisplayName("el panel ve HALF_OPEN cuando vence la apertura, sin mover el breaker real")
    void healthShowsEffectiveState() {
        for (int i = 0; i < Harness.FAILURE_THRESHOLD; i++) {
            h.stripe.on(AUTHORIZE, GatewayResponse.error("x", LATENCY));
            h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_" + i, LATENCY));
            h.authorize("k" + i);
        }

        var later = h.clock.instant().plus(Harness.OPEN_DURATION);
        var stripeHealth = h.health.health(later).stream()
                .filter(v -> v.profile().id().equals(STRIPE)).findFirst().orElseThrow();

        assertThat(stripeHealth.circuit().state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(stripeHealth.circuit().retryAt()).isEqualTo(Optional.empty());
        assertThat(h.breakers.get(STRIPE).state()).isEqualTo(CircuitState.OPEN);
    }

    /** Adaptador que incumple el contrato lanzando una excepción. */
    private static final class BrokenGateway implements PaymentGateway {

        @Override
        public GatewayId id() {
            return GatewayId.of("broken");
        }

        @Override
        public GatewayResponse authorize(GatewayAuthorization a) {
            throw new IllegalStateException("conexión rechazada");
        }

        @Override
        public GatewayResponse capture(String ref, Money amount) {
            throw new IllegalStateException();
        }

        @Override
        public GatewayResponse refund(String ref, Money amount) {
            throw new IllegalStateException();
        }

        @Override
        public GatewayResponse cancel(String ref) {
            throw new IllegalStateException();
        }
    }
}
