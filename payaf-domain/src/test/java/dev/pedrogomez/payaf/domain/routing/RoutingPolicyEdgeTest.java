package dev.pedrogomez.payaf.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bordes, prioridad de los descartes, desempates, determinismo y el caso realista de la política. */
class RoutingPolicyEdgeTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final RoutingPolicy POLICY = new RoutingPolicy();
    private static final CountryCode ES = CountryCode.of("ES");
    private static final CountryCode US = CountryCode.of("US");

    private static PaymentRequest request(long minor, Currency currency, CountryCode country) {
        return new PaymentRequest(Money.of(minor, currency), country, "pedido-r", IdempotencyKey.of("k-r"));
    }

    private static GatewayProfile profile(String id, Set<Currency> currencies, Set<CountryCode> countries, int bps,
                                          Map<Currency, Long> fixed, boolean enabled) {
        return new GatewayProfile(GatewayId.of(id), currencies, countries, bps, fixed, enabled);
    }

    private static CircuitBreaker openBreaker(String id, Instant openedAt, Duration openFor) {
        CircuitBreaker b = new CircuitBreaker(GatewayId.of(id), CircuitBreakerConfig.of(1, openFor));
        b.recordFailure(openedAt);
        b.pullEvents();
        return b;
    }

    private static List<String> ranking(RoutingDecision d) {
        return d.candidates().stream().map(c -> c.gateway().value()).toList();
    }

    // ---------------------------------------------------------------------------------
    // Caso realista: Stripe, Adyen y Redsys
    // ---------------------------------------------------------------------------------

    private static final GatewayProfile STRIPE = profile("stripe", Set.of(Currency.EUR, Currency.USD), Set.of(), 290,
            Map.of(Currency.EUR, 25L, Currency.USD, 25L), true);
    private static final GatewayProfile ADYEN = profile("adyen", Set.of(Currency.EUR, Currency.USD), Set.of(), 250,
            Map.of(Currency.EUR, 30L, Currency.USD, 30L), true);
    private static final GatewayProfile REDSYS = profile("redsys", Set.of(Currency.EUR), Set.of(ES), 120,
            Map.of(), true);
    private static final List<GatewayProfile> REALISTIC = List.of(STRIPE, ADYEN, REDSYS);

    @Test
    @DisplayName("realista: 100 € en ES → Redsys (1,20 €), Adyen (2,80 €), Stripe (3,15 €)")
    void realisticEurInSpain() {
        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, ES), REALISTIC, Map.of(), NOW);

        assertThat(d.candidates()).containsExactly(
                new RoutingCandidate(GatewayId.of("redsys"), Money.of(120, Currency.EUR), CircuitState.CLOSED),
                new RoutingCandidate(GatewayId.of("adyen"), Money.of(280, Currency.EUR), CircuitState.CLOSED),
                new RoutingCandidate(GatewayId.of("stripe"), Money.of(315, Currency.EUR), CircuitState.CLOSED));
        assertThat(d.rejections()).isEmpty();
    }

    @Test
    @DisplayName("realista: 100 USD en US → Adyen (2,80 $) y Stripe (3,15 $); Redsys descartada por la divisa")
    void realisticUsdInUs() {
        RoutingDecision d = POLICY.decide(request(10_000, Currency.USD, US), REALISTIC, Map.of(), NOW);

        assertThat(d.primary()).contains(
                new RoutingCandidate(GatewayId.of("adyen"), Money.of(280, Currency.USD), CircuitState.CLOSED));
        assertThat(d.candidates()).extracting(RoutingCandidate::estimatedFee)
                .containsExactly(Money.of(280, Currency.USD), Money.of(315, Currency.USD));
        assertThat(d.rejections()).containsExactly(
                new RoutingRejection(GatewayId.of("redsys"), RejectionReason.CURRENCY_NOT_SUPPORTED));
    }

    @Test
    @DisplayName("realista: 100 € en US → Redsys descartada por el país")
    void realisticEurOutsideSpain() {
        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, US), REALISTIC, Map.of(), NOW);
        assertThat(ranking(d)).containsExactly("adyen", "stripe");
        assertThat(d.rejections()).containsExactly(
                new RoutingRejection(GatewayId.of("redsys"), RejectionReason.COUNTRY_NOT_SUPPORTED));
    }

    @Test
    @DisplayName("realista: con Redsys OPEN, el pago en ES va a Adyen; al vencer el tiempo Redsys vuelve en HALF_OPEN y primera")
    void realisticFailoverWhenRedsysIsOpen() {
        CircuitBreaker redsys = openBreaker("redsys", NOW.minusSeconds(10), Duration.ofSeconds(60));
        Map<GatewayId, CircuitBreaker> breakers = Map.of(REDSYS.id(), redsys);
        PaymentRequest req = request(10_000, Currency.EUR, ES);

        RoutingDecision d = POLICY.decide(req, REALISTIC, breakers, NOW);
        assertThat(d.primary()).contains(
                new RoutingCandidate(GatewayId.of("adyen"), Money.of(280, Currency.EUR), CircuitState.CLOSED));
        assertThat(ranking(d)).containsExactly("adyen", "stripe");
        assertThat(d.rejections()).containsExactly(new RoutingRejection(REDSYS.id(), RejectionReason.CIRCUIT_OPEN));

        RoutingDecision later = POLICY.decide(req, REALISTIC, breakers, NOW.plusSeconds(50));
        assertThat(later.primary()).contains(
                new RoutingCandidate(REDSYS.id(), Money.of(120, Currency.EUR), CircuitState.HALF_OPEN));
        assertThat(later.rejections()).isEmpty();
        assertThat(redsys.tryAcquirePermission(NOW.plusSeconds(50)))
                .as("decidir no gasta la petición de prueba de HALF_OPEN").isTrue();
    }

    @Test
    @DisplayName("todas las pasarelas caídas: no hay candidatas y cada una explica por qué")
    void allGatewaysDown() {
        Map<GatewayId, CircuitBreaker> breakers = Map.of(
                STRIPE.id(), openBreaker("stripe", NOW, Duration.ofMinutes(1)),
                ADYEN.id(), openBreaker("adyen", NOW, Duration.ofMinutes(1)),
                REDSYS.id(), openBreaker("redsys", NOW, Duration.ofMinutes(1)));
        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, ES), REALISTIC, breakers, NOW);
        assertThat(d.hasCandidates()).isFalse();
        assertThat(d.primary()).isEmpty();
        assertThat(d.rejections()).extracting(RoutingRejection::reason).containsOnly(RejectionReason.CIRCUIT_OPEN);
        assertThat(d.rejections()).extracting(RoutingRejection::gateway)
                .containsExactlyInAnyOrder(STRIPE.id(), ADYEN.id(), REDSYS.id());
    }

    // ---------------------------------------------------------------------------------
    // Prioridad de los motivos de descarte
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("un solo motivo por pasarela y en orden de prioridad: DISABLED > CURRENCY > COUNTRY > CIRCUIT_OPEN")
    void rejectionPriority() {
        PaymentRequest req = request(1_000, Currency.EUR, ES);
        Set<Currency> usd = Set.of(Currency.USD);
        Set<CountryCode> gb = Set.of(CountryCode.of("GB"));
        List<GatewayProfile> profiles = List.of(
                profile("all-wrong", usd, gb, 10, Map.of(), false),
                profile("off-but-fine", Set.of(Currency.EUR), Set.of(), 10, Map.of(), false),
                profile("cur-cty-open", usd, gb, 10, Map.of(), true),
                profile("cur-open", usd, Set.of(), 10, Map.of(), true),
                profile("cty-open", Set.of(Currency.EUR), gb, 10, Map.of(), true),
                profile("only-open", Set.of(Currency.EUR), Set.of(ES), 10, Map.of(), true));
        Map<GatewayId, CircuitBreaker> breakers = new HashMap<>();
        for (GatewayProfile p : profiles) {
            breakers.put(p.id(), openBreaker(p.id().value(), NOW, Duration.ofMinutes(5)));
        }

        RoutingDecision d = POLICY.decide(req, profiles, breakers, NOW);

        assertThat(d.candidates()).isEmpty();
        // Un único motivo por pasarela, el de más prioridad; la lista sale ordenada por GatewayId.
        assertThat(d.rejections()).containsExactly(
                new RoutingRejection(GatewayId.of("all-wrong"), RejectionReason.DISABLED),
                new RoutingRejection(GatewayId.of("cty-open"), RejectionReason.COUNTRY_NOT_SUPPORTED),
                new RoutingRejection(GatewayId.of("cur-cty-open"), RejectionReason.CURRENCY_NOT_SUPPORTED),
                new RoutingRejection(GatewayId.of("cur-open"), RejectionReason.CURRENCY_NOT_SUPPORTED),
                new RoutingRejection(GatewayId.of("off-but-fine"), RejectionReason.DISABLED),
                new RoutingRejection(GatewayId.of("only-open"), RejectionReason.CIRCUIT_OPEN));
    }

    @Test
    @DisplayName("el breaker solo se consulta si la pasarela podría atender el pago: no se le provoca HALF_OPEN")
    void breakerNotConsultedWhenRejectedEarlier() {
        CircuitBreaker expired = openBreaker("off", NOW.minusSeconds(600), Duration.ofSeconds(10));
        GatewayProfile disabled = profile("off", Set.of(Currency.EUR), Set.of(), 10, Map.of(), false);
        CircuitBreaker expiredUsd = openBreaker("usd-only", NOW.minusSeconds(600), Duration.ofSeconds(10));
        GatewayProfile usdOnly = profile("usd-only", Set.of(Currency.USD), Set.of(), 10, Map.of(), true);

        POLICY.decide(request(1_000, Currency.EUR, ES), List.of(disabled, usdOnly),
                Map.of(disabled.id(), expired, usdOnly.id(), expiredUsd), NOW);

        assertThat(expired.state()).isEqualTo(CircuitState.OPEN);
        assertThat(expiredUsd.state()).isEqualTo(CircuitState.OPEN);
        assertThat(expired.pullEvents()).isEmpty();
        assertThat(expiredUsd.pullEvents()).isEmpty();
    }

    @Test
    @DisplayName("HALF_OPEN con la prueba ya concedida se descarta como CIRCUIT_OPEN")
    void halfOpenWithTrialInFlightIsRejected() {
        CircuitBreaker b = openBreaker("busy", NOW.minusSeconds(60), Duration.ofSeconds(10));
        assertThat(b.tryAcquirePermission(NOW)).isTrue();
        GatewayProfile busy = profile("busy", Set.of(Currency.EUR), Set.of(), 10, Map.of(), true);
        RoutingDecision d = POLICY.decide(request(1_000, Currency.EUR, ES), List.of(busy), Map.of(busy.id(), b), NOW);
        assertThat(d.rejections()).containsExactly(new RoutingRejection(busy.id(), RejectionReason.CIRCUIT_OPEN));
    }

    // ---------------------------------------------------------------------------------
    // Orden y desempates
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("la comisión manda sobre el estado del circuito: HALF_OPEN más barata va antes que CLOSED más cara")
    void feeDominatesCircuitState() {
        CircuitBreaker recovering = openBreaker("cheap", NOW.minusSeconds(60), Duration.ofSeconds(10));
        List<GatewayProfile> profiles = List.of(
                profile("pricey", Set.of(Currency.EUR), Set.of(), 101, Map.of(), true),
                profile("cheap", Set.of(Currency.EUR), Set.of(), 100, Map.of(), true));
        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, ES), profiles,
                Map.of(GatewayId.of("cheap"), recovering), NOW);
        assertThat(ranking(d)).containsExactly("cheap", "pricey");
        assertThat(d.candidates().getFirst().circuitState()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("la comisión se compara ya redondeada: 100 pb y 101 pb sobre 0,49 € empatan a 0 y desempata el estado")
    void tieAfterRounding() {
        // 0,49 € × 1,00 % = 0,49 cént → 0;  0,49 € × 1,01 % = 0,4949 cént → 0
        CircuitBreaker recovering = openBreaker("aaa", NOW.minusSeconds(60), Duration.ofSeconds(10));
        List<GatewayProfile> profiles = List.of(
                profile("aaa", Set.of(Currency.EUR), Set.of(), 100, Map.of(), true),
                profile("zzz", Set.of(Currency.EUR), Set.of(), 101, Map.of(), true));
        RoutingDecision d = POLICY.decide(request(49, Currency.EUR, ES), profiles,
                Map.of(GatewayId.of("aaa"), recovering), NOW);
        assertThat(ranking(d)).containsExactly("zzz", "aaa");
        assertThat(d.candidates()).extracting(RoutingCandidate::estimatedFee).containsOnly(Money.zero(Currency.EUR));
    }

    @Test
    @DisplayName("empate total: decide el GatewayId en orden lexicográfico, sin importar el orden de entrada")
    void tieBrokenByGatewayId() {
        List<GatewayProfile> profiles = new ArrayList<>();
        for (String id : List.of("gw-10", "gw-2", "gw-1", "a-1", "b0")) {
            profiles.add(profile(id, Set.of(Currency.EUR), Set.of(), 100, Map.of(), true));
        }
        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, ES), profiles, Map.of(), NOW);
        assertThat(ranking(d)).containsExactly("a-1", "b0", "gw-1", "gw-10", "gw-2");
    }

    @Test
    @DisplayName("una pasarela sin breaker en el mapa cuenta como CLOSED")
    void missingBreakerIsClosed() {
        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, ES), List.of(STRIPE), Map.of(), NOW);
        assertThat(d.candidates()).singleElement()
                .extracting(RoutingCandidate::circuitState).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("la comisión fija usa la de la divisa del pago, y cero si no está configurada")
    void fixedFeePerCurrency() {
        GatewayProfile p = profile("multi", Set.of(Currency.EUR, Currency.USD, Currency.JPY), Set.of(), 0,
                Map.of(Currency.EUR, 25L, Currency.USD, 30L), true);
        assertThat(p.estimatedFee(Money.of(10_000, Currency.EUR))).isEqualTo(Money.of(25, Currency.EUR));
        assertThat(p.estimatedFee(Money.of(10_000, Currency.USD))).isEqualTo(Money.of(30, Currency.USD));
        assertThat(p.estimatedFee(Money.of(10_000, Currency.JPY))).isEqualTo(Money.zero(Currency.JPY));
        assertThatThrownBy(() -> p.estimatedFee(Money.of(1, Currency.GBP))).isInstanceOf(InvalidValueException.class);
    }

    // ---------------------------------------------------------------------------------
    // Determinismo
    // ---------------------------------------------------------------------------------

    private static List<GatewayProfile> mixedProfiles() {
        return List.of(
                STRIPE, ADYEN, REDSYS,
                profile("same-fee-a", Set.of(Currency.EUR), Set.of(), 280, Map.of(), true),
                profile("same-fee-b", Set.of(Currency.EUR), Set.of(), 280, Map.of(), true),
                profile("half-open", Set.of(Currency.EUR), Set.of(), 280, Map.of(), true),
                profile("open", Set.of(Currency.EUR), Set.of(), 10, Map.of(), true),
                profile("disabled", Set.of(Currency.EUR), Set.of(), 1, Map.of(), false),
                profile("usd", Set.of(Currency.USD), Set.of(), 1, Map.of(), true),
                profile("gb", Set.of(Currency.EUR), Set.of(CountryCode.of("GB")), 1, Map.of(), true));
    }

    private static Map<GatewayId, CircuitBreaker> mixedBreakers() {
        return Map.of(
                GatewayId.of("half-open"), openBreaker("half-open", NOW.minusSeconds(120), Duration.ofSeconds(60)),
                GatewayId.of("open"), openBreaker("open", NOW, Duration.ofSeconds(60)));
    }

    @Property(tries = 300)
    @Label("es determinista: barajar los perfiles no cambia las candidatas ni los descartes, ordenados por GatewayId")
    void shuffledProfilesGiveSameCandidates(@ForAll long seed) {
        RoutingDecision reference = POLICY.decide(request(10_000, Currency.EUR, ES), mixedProfiles(), mixedBreakers(), NOW);
        List<GatewayProfile> shuffled = new ArrayList<>(mixedProfiles());
        Collections.shuffle(shuffled, new Random(seed));

        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, ES), shuffled, mixedBreakers(), NOW);

        assertThat(d.candidates()).isEqualTo(reference.candidates());
        assertThat(d.rejections()).isEqualTo(reference.rejections());
        assertThat(d.rejections()).extracting(RoutingRejection::gateway).isSorted();
        // redsys 1,20 €; a 2,80 € empatan adyen, same-fee-a, same-fee-b (CLOSED, por id) y half-open; stripe 3,15
        assertThat(ranking(d)).containsExactly("redsys", "adyen", "same-fee-a", "same-fee-b", "half-open", "stripe");
        assertThat(d.candidates().get(4).circuitState()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Property(tries = 300)
    @Label("es determinista del todo: barajar los perfiles da exactamente la misma RoutingDecision")
    void shuffledProfilesGiveIdenticalDecision(@ForAll long seed) {
        PaymentRequest req = request(10_000, Currency.USD, US);
        RoutingDecision reference = POLICY.decide(req, mixedProfiles(), mixedBreakers(), NOW);
        List<GatewayProfile> shuffled = new ArrayList<>(mixedProfiles());
        Collections.shuffle(shuffled, new Random(seed));
        assertThat(POLICY.decide(req, shuffled, mixedBreakers(), NOW)).isEqualTo(reference);
    }

    @Property(tries = 300)
    @Label("cada pasarela configurada aparece exactamente una vez: o candidata o descartada")
    void everyGatewayAccountedForOnce(@ForAll long seed, @ForAll @IntRange(min = 1, max = 2_000_000) int amount) {
        List<GatewayProfile> shuffled = new ArrayList<>(mixedProfiles());
        Collections.shuffle(shuffled, new Random(seed));
        RoutingDecision d = POLICY.decide(request(amount, Currency.EUR, ES), shuffled, mixedBreakers(), NOW);
        List<GatewayId> seen = Stream.concat(
                d.candidates().stream().map(RoutingCandidate::gateway),
                d.rejections().stream().map(RoutingRejection::gateway)).toList();
        assertThat(seen).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(shuffled.stream().map(GatewayProfile::id).toList());
        assertThat(d.candidates()).extracting(c -> c.estimatedFee().amountMinor()).isSorted();
        assertThat(d.candidates()).allSatisfy(c -> assertThat(c.circuitState()).isIn(CircuitState.CLOSED,
                CircuitState.HALF_OPEN));
    }

    // ---------------------------------------------------------------------------------
    // Errores de configuración y nulos
    // ---------------------------------------------------------------------------------

    @Example
    @Label("un id duplicado es un error aunque la segunda copia esté deshabilitada o sea distinta")
    void duplicateIdIsError() {
        GatewayProfile copy = profile("stripe", Set.of(Currency.GBP), Set.of(), 1, Map.of(), false);
        assertThatThrownBy(() -> POLICY.decide(request(1_000, Currency.EUR, ES), List.of(STRIPE, ADYEN, copy),
                Map.of(), NOW)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> POLICY.decide(request(1_000, Currency.EUR, ES), List.of(copy, STRIPE),
                Map.of(), NOW)).isInstanceOf(InvalidValueException.class);
    }

    @Example
    @Label("sin perfiles no hay candidatas; perfiles o argumentos nulos se rechazan")
    void emptyAndNulls() {
        RoutingDecision empty = POLICY.decide(request(1_000, Currency.EUR, ES), List.of(), Map.of(), NOW);
        assertThat(empty.hasCandidates()).isFalse();
        assertThat(empty.rejections()).isEmpty();
        PaymentRequest req = request(1_000, Currency.EUR, ES);
        assertThatThrownBy(() -> POLICY.decide(req, Arrays.asList(STRIPE, null), Map.of(), NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> POLICY.decide(null, List.of(), Map.of(), NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> POLICY.decide(req, null, Map.of(), NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> POLICY.decide(req, List.of(), null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> POLICY.decide(req, List.of(), Map.of(), null)).isInstanceOf(NullPointerException.class);
    }

    @Example
    @Label("RoutingDecision es inmutable: ni sus listas ni las de entrada la cambian después")
    void decisionIsImmutable() {
        RoutingDecision d = POLICY.decide(request(10_000, Currency.EUR, ES), REALISTIC, Map.of(), NOW);
        assertThatThrownBy(() -> d.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> d.rejections().clear()).isInstanceOf(UnsupportedOperationException.class);
        List<RoutingCandidate> source = new ArrayList<>(d.candidates());
        RoutingDecision copy = new RoutingDecision(source, List.of());
        source.clear();
        assertThat(copy.candidates()).hasSize(3);
    }

    @Example
    @Label("GatewayProfile valida comisiones, divisas y hace copias defensivas")
    void profileValidation() {
        GatewayId id = GatewayId.of("gw");
        Set<Currency> eur = Set.of(Currency.EUR);
        assertThatThrownBy(() -> new GatewayProfile(id, Set.of(), Set.of(), 0, Map.of(), true))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayProfile(id, eur, Set.of(), -1, Map.of(), true))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayProfile(id, eur, Set.of(), 10_001, Map.of(), true))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayProfile(id, eur, Set.of(), 0, Map.of(Currency.EUR, -1L), true))
                .isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new GatewayProfile(id, eur, Set.of(), 0, Map.of(Currency.USD, 1L), true))
                .isInstanceOf(InvalidValueException.class);
        assertThat(new GatewayProfile(id, eur, Set.of(), 10_000, Map.of(), true).estimatedFee(Money.of(77, Currency.EUR)))
                .isEqualTo(Money.of(77, Currency.EUR));

        Set<Currency> mutable = new HashSet<>(eur);
        Set<CountryCode> countries = new HashSet<>(Set.of(ES));
        Map<Currency, Long> fees = new HashMap<>(Map.of(Currency.EUR, 5L));
        GatewayProfile p = new GatewayProfile(id, mutable, countries, 0, fees, true);
        mutable.add(Currency.USD);
        countries.clear();
        fees.put(Currency.EUR, 999L);
        assertThat(p.supports(Currency.USD)).isFalse();
        assertThat(p.supports(US)).as("vaciar el set original no lo convierte en «todos los países»").isFalse();
        assertThat(p.estimatedFee(Money.of(0, Currency.EUR))).isEqualTo(Money.of(5, Currency.EUR));
    }
}
