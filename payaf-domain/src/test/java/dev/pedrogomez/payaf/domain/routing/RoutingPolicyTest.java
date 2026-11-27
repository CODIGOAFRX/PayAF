package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutingPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final RoutingPolicy POLICY = new RoutingPolicy();

    private static final PaymentRequest REQUEST = new PaymentRequest(
            Money.of(10_000, Currency.EUR), CountryCode.of("ES"), "pedido-1", IdempotencyKey.of("k"));

    private static GatewayProfile profile(String id, int bp, long fixedEur) {
        return new GatewayProfile(GatewayId.of(id), Set.of(Currency.EUR, Currency.USD), Set.of(), bp,
                Map.of(Currency.EUR, fixedEur), true);
    }

    private static List<String> ranking(RoutingDecision decision) {
        return decision.candidates().stream().map(c -> c.gateway().value()).toList();
    }

    @Test
    @DisplayName("estimatedFee suma la parte variable y la fija")
    void estimatedFee() {
        assertThat(profile("gw-a", 290, 25).estimatedFee(REQUEST.amount())).isEqualTo(Money.of(315, Currency.EUR));
    }

    @Test
    @DisplayName("ordena por comisión estimada ascendente")
    void ranksByFee() {
        RoutingDecision decision = POLICY.decide(REQUEST,
                List.of(profile("cara", 300, 0), profile("barata", 100, 0), profile("media", 200, 0)),
                Map.of(), NOW);

        assertThat(ranking(decision)).containsExactly("barata", "media", "cara");
        assertThat(decision.primary()).hasValueSatisfying(c -> {
            assertThat(c.gateway()).isEqualTo(GatewayId.of("barata"));
            assertThat(c.estimatedFee()).isEqualTo(Money.of(100, Currency.EUR));
        });
        assertThat(decision.rejections()).isEmpty();
    }

    @Test
    @DisplayName("a igual comisión, CLOSED antes que HALF_OPEN y después por GatewayId")
    void tieBreaks() {
        CircuitBreaker recovering = new CircuitBreaker(GatewayId.of("aaa"), CircuitBreakerConfig.of(1, Duration.ofSeconds(10)));
        recovering.recordFailure(NOW.minusSeconds(60));

        RoutingDecision decision = POLICY.decide(REQUEST,
                List.of(profile("aaa", 100, 0), profile("ccc", 100, 0), profile("bbb", 100, 0)),
                Map.of(GatewayId.of("aaa"), recovering), NOW);

        assertThat(ranking(decision)).containsExactly("bbb", "ccc", "aaa");
        assertThat(decision.candidates().getLast().circuitState()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("descarta y explica: deshabilitada, divisa, país y circuito abierto")
    void rejectionsCarryReason() {
        GatewayProfile disabled = new GatewayProfile(GatewayId.of("off"), Set.of(Currency.EUR), Set.of(), 50,
                Map.of(), false);
        GatewayProfile usdOnly = new GatewayProfile(GatewayId.of("usd"), Set.of(Currency.USD), Set.of(), 50,
                Map.of(), true);
        GatewayProfile ukOnly = new GatewayProfile(GatewayId.of("uk"), Set.of(Currency.EUR),
                Set.of(CountryCode.of("GB")), 50, Map.of(), true);
        GatewayProfile broken = profile("broken", 50, 0);
        CircuitBreaker open = new CircuitBreaker(broken.id(), CircuitBreakerConfig.of(1, Duration.ofMinutes(5)));
        open.recordFailure(NOW);

        RoutingDecision decision = POLICY.decide(REQUEST,
                List.of(disabled, usdOnly, ukOnly, broken, profile("ok", 300, 0)),
                Map.of(broken.id(), open), NOW);

        assertThat(ranking(decision)).containsExactly("ok");
        // Los descartes salen ordenados por GatewayId: broken, off, uk, usd.
        assertThat(decision.rejections()).extracting(RoutingRejection::reason).containsExactly(
                RejectionReason.CIRCUIT_OPEN, RejectionReason.DISABLED,
                RejectionReason.COUNTRY_NOT_SUPPORTED, RejectionReason.CURRENCY_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("sin candidatas, primary está vacío")
    void noCandidates() {
        RoutingDecision decision = POLICY.decide(REQUEST, List.of(), Map.of(), NOW);
        assertThat(decision.hasCandidates()).isFalse();
        assertThat(decision.primary()).isEmpty();
    }
}
