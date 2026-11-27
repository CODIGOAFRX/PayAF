package dev.pedrogomez.payaf;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewayChaosControl;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedGatewayCatalog;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.ReapStalePaymentsUseCase;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * El hexágono entero cableado por Spring con el perfil memory: casos de uso reales, persistencia
 * en memoria y las PSP simuladas. Comprueba el enrutado por comisión, el failover cuando una PSP
 * cae (vía la ChaosControl) y que un rechazo del emisor no provoca failover.
 */
@SpringBootTest
@ActiveProfiles("memory")
class MemoryProfileRoutingTest {

    @Autowired
    private AuthorizePaymentUseCase authorize;

    @Autowired
    private CapturePaymentUseCase capture;

    @Autowired
    private GatewayChaosControl chaos;

    @Autowired
    private CircuitBreakerConfig breakerConfig;

    @Autowired
    private TransactionRunner transactions;

    @Autowired
    private ReapStalePaymentsUseCase reaper;

    @AfterEach
    void resetChaos() {
        chaos.resetAll();
    }

    private static AuthorizationResult authorizeIn(AuthorizePaymentUseCase useCase, long amountMinor) {
        String key = "it-" + UUID.randomUUID();
        return useCase.authorize(new AuthorizePaymentCommand(new PaymentRequest(Money.of(amountMinor, Currency.EUR),
                CountryCode.of("ES"), "pedido-" + key, IdempotencyKey.of(key))));
    }

    @Test
    @DisplayName("100 € en España van a redsys, la más barata, y quedan AUTHORIZED")
    void cheapestGatewayWins() {
        AuthorizationResult result = authorizeIn(authorize, 10_000);

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.payment().authorizedBy()).contains(SimulatedGatewayCatalog.REDSYS);
        assertThat(result.payment().gatewayReference().orElseThrow()).hasSize(12);
        assertThat(result.decision().candidates()).extracting(c -> c.gateway().value())
                .containsExactly("redsys", "adyen", "stripe", "mock");

        assertThat(capture.capture(result.payment().id()).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("con redsys caída (ChaosControl) el pago hace failover a adyen")
    void failoverWhenRedsysIsDown() {
        chaos.set(SimulatedGatewayCatalog.REDSYS, FaultConfig.none().withDown(true));

        AuthorizationResult result = authorizeIn(authorize, 10_000);

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.payment().authorizedBy()).contains(SimulatedGatewayCatalog.ADYEN);
        assertThat(result.attempts()).extracting(GatewayAttempt::gateway, GatewayAttempt::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SimulatedGatewayCatalog.REDSYS, AttemptOutcome.ERROR),
                        org.assertj.core.groups.Tuple.tuple(SimulatedGatewayCatalog.ADYEN, AttemptOutcome.APPROVED));
        assertThat(result.attempts().getFirst().code()).contains(FaultInjector.HTTP_503);
    }

    @Test
    @DisplayName("un importe terminado en 51 queda FAILED DECLINED en redsys, sin failover")
    void declineDoesNotFailOver() {
        AuthorizationResult result = authorizeIn(authorize, 10_051);

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains("DECLINED: 0190");
        assertThat(result.attempts()).singleElement()
                .satisfies(a -> {
                    assertThat(a.gateway()).isEqualTo(SimulatedGatewayCatalog.REDSYS);
                    assertThat(a.outcome()).isEqualTo(AttemptOutcome.DECLINED);
                });
    }

    @Test
    @DisplayName("la configuración del breaker sale de application.yml y el reaper está cableado")
    void wiring() {
        assertThat(breakerConfig).isEqualTo(CircuitBreakerConfig.of(3, Duration.ofSeconds(15)));
        assertThat(transactions.inTransaction(() -> "ok")).isEqualTo("ok");
        assertThat(reaper.reap(Duration.ofHours(1)).total()).isZero();
    }
}
