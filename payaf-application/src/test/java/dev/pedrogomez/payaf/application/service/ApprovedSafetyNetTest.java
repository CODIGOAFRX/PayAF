package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.OPEN_DURATION;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.fake.MutableClock;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La red de seguridad no convierte nunca un APPROVED de la PSP en un FAILED. */
class ApprovedSafetyNetTest {

    private static final GatewayId A = GatewayId.of("gw-a");
    private static final GatewayId B = GatewayId.of("gw-b");

    /** Reloj que deja de funcionar para siempre en cuanto se rompe. */
    private static final class BreakableClock extends Clock {
        private final MutableClock delegate;
        private final AtomicBoolean broken = new AtomicBoolean();

        BreakableClock(MutableClock delegate) {
            this.delegate = delegate;
        }

        void breakNow() {
            broken.set(true);
        }

        @Override
        public Instant instant() {
            if (broken.get()) {
                throw new IllegalStateException("reloj caído");
            }
            return delegate.instant();
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private Harness h;

    @AfterEach
    void discipline() {
        h.assertTransactionDiscipline();
        Thread.interrupted(); // no dejar el flag puesto para otros tests
    }

    @Test
    @DisplayName("si el reloj cae justo tras el APPROVED de una prueba de HALF_OPEN, el pago queda AUTHORIZED y el breaker se cierra")
    void approvedSurvivesClockFailure() {
        MutableClock base = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
        BreakableClock clock = new BreakableClock(base);
        h = new Harness(CircuitBreakerConfig.of(2, OPEN_DURATION), false, clock);
        FunctionalGateway a = new FunctionalGateway(A, h.transactions, auth -> {
            clock.breakNow();
            return GatewayResponse.approved("ref-a", LATENCY);
        });
        FunctionalGateway b = new FunctionalGateway(B, h.transactions, auth -> {
            throw new AssertionError("no hay failover tras un APPROVED");
        });
        h.catalog.register(Harness.profile(A, 100), a).register(Harness.profile(B, 200), b);
        CircuitBreaker breaker = h.breakers.get(A);
        breaker.recordFailure(base.instant());
        breaker.recordFailure(base.instant());
        base.advance(OPEN_DURATION);
        PaymentRequest request = Harness.request("k1", 10_000);

        AuthorizationResult result = h.authorize(request);

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.payment().authorizedBy()).contains(A);
        assertThat(result.payment().gatewayReference()).contains("ref-a");
        assertThat(result.attempts()).extracting(GatewayAttempt::outcome).containsExactly(AttemptOutcome.APPROVED);
        // El breaker respondió bien: se registra el éxito, no un ERROR.
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.snapshot().trialsInFlight()).isZero();
        assertThat(h.idempotency.isCompleted(request.key())).isTrue();
        assertThat(h.payments.stored(result.payment().id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("una InterruptedException del adaptador es un ERROR y se restaura el flag de interrupción")
    void interruptedExceptionRestoresFlag() {
        h = new Harness(CircuitBreakerConfig.of(2, OPEN_DURATION), false);
        h.catalog.register(Harness.profile(A, 100), new FunctionalGateway(A, h.transactions, auth -> {
            throw FunctionalGateway.sneakyThrow(new InterruptedException("apagando"));
        }));
        h.catalog.register(Harness.profile(B, 200),
                new FunctionalGateway(B, h.transactions, auth -> GatewayResponse.approved("ref-b", LATENCY)));

        AuthorizationResult result = h.authorize("k1");

        assertThat(Thread.interrupted()).as("flag de interrupción restaurado").isTrue();
        assertThat(result.attempts().getFirst().code()).isEqualTo(Optional.of(ServiceSupport.ADAPTER_EXCEPTION));
        assertThat(result.payment().authorizedBy()).contains(B);
    }
}
