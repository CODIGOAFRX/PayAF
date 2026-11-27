package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.RecordingSleeper;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient.RedsysRequest;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient.RedsysResponse;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tester (fase 4a): el simulador de PSP como capa anticorrupción, atacado desde el puerto
 * {@link PaymentGateway} con el {@link GatewaySimulator} completo, como lo cablea bootstrap.
 */
class GatewaySimAttackTest {

    private static final Pattern STRIPE_PI = Pattern.compile("pi_[A-Za-z0-9]{24}");
    private static final Pattern STRIPE_RE = Pattern.compile("re_[A-Za-z0-9]{24}");
    private static final Pattern ADYEN_PSP = Pattern.compile("[0-9]{16}");
    private static final Pattern REDSYS_ORDER = Pattern.compile("[0-9]{4}[A-Za-z0-9]{8}");
    private static final Pattern REDSYS_AUTH_CODE = Pattern.compile("[0-9]{6}");

    private static final Map<GatewayId, String> DECLINE_CODE = Map.of(
            SimulatedGatewayCatalog.STRIPE, "insufficient_funds",
            SimulatedGatewayCatalog.ADYEN, "Not enough balance",
            SimulatedGatewayCatalog.REDSYS, "0190",
            SimulatedGatewayCatalog.MOCK, "mock_decline_51");

    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final GatewaySimulator sim = GatewaySimulator.create(new Random(7), sleeper);

    static Stream<GatewayId> allGateways() {
        return Stream.of(SimulatedGatewayCatalog.STRIPE, SimulatedGatewayCatalog.ADYEN, SimulatedGatewayCatalog.REDSYS,
                SimulatedGatewayCatalog.MOCK);
    }

    private PaymentGateway gw(GatewayId id) {
        return sim.catalog().gateway(id).orElseThrow();
    }

    private SimulatedLedger ledger(GatewayId id) {
        return switch (id.value()) {
            case "stripe" -> sim.stripe().ledger();
            case "adyen" -> sim.adyen().ledger();
            case "redsys" -> sim.redsys().ledger();
            case "mock" -> sim.mock().ledger();
            default -> throw new IllegalArgumentException(id.toString());
        };
    }

    private static Money eur(long minor) {
        return Money.of(minor, Currency.EUR);
    }

    private String authorized(GatewayId id, long minor) {
        GatewayResponse r = gw(id).authorize(SimFixtures.authorization(minor));
        assertThat(r.outcome()).as("%s autoriza %d", id, minor).isEqualTo(AttemptOutcome.APPROVED);
        return r.gatewayReference().orElseThrow();
    }

    private static void assertDeclined(GatewayResponse r, String what) {
        assertThat(r.outcome()).as("%s debe ser DECLINED (nunca ERROR): %s", what, r).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(r.code()).as("%s lleva el código nativo", what).isPresent();
    }

    // ---------------------------------------------------------------------------------
    // Formatos nativos y unicidad bajo concurrencia
    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("formatos nativos")
    class Formats {

        /** 8 hilos x {@code perThread} autorizaciones (y un reembolso de cada una) a la vez. */
        private List<String[]> hammer(GatewayId id, int perThread) throws Exception {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<List<String[]>>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        List<String[]> refs = new ArrayList<>();
                        for (int i = 0; i < perThread; i++) {
                            GatewayResponse auth = gw(id).authorize(SimFixtures.authorization(10_000));
                            assertThat(auth.outcome()).isEqualTo(AttemptOutcome.APPROVED);
                            String ref = auth.gatewayReference().orElseThrow();
                            GatewayResponse cap = gw(id).capture(ref, eur(10_000));
                            assertThat(cap.outcome()).isEqualTo(AttemptOutcome.APPROVED);
                            GatewayResponse refund = gw(id).refund(ref, eur(1_000));
                            assertThat(refund.outcome()).isEqualTo(AttemptOutcome.APPROVED);
                            refs.add(new String[] {ref, refund.gatewayReference().orElseThrow(),
                                    cap.gatewayReference().orElseThrow()});
                        }
                        return refs;
                    }));
                }
                start.countDown();
                List<String[]> all = new ArrayList<>();
                for (Future<List<String[]>> f : futures) {
                    all.addAll(f.get(60, TimeUnit.SECONDS));
                }
                return all;
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("Stripe: 2.000 PaymentIntents pi_+24 y reembolsos re_+24, sin repetir, desde 8 hilos")
        void stripeFormatsAndUniqueness() throws Exception {
            List<String[]> refs = hammer(SimulatedGatewayCatalog.STRIPE, 250);

            Set<String> intents = new HashSet<>();
            Set<String> refunds = new HashSet<>();
            for (String[] r : refs) {
                assertThat(r[0]).matches(STRIPE_PI);
                assertThat(r[1]).matches(STRIPE_RE);
                assertThat(r[2]).as("la captura devuelve el mismo PaymentIntent").isEqualTo(r[0]);
                intents.add(r[0]);
                refunds.add(r[1]);
            }
            assertThat(intents).hasSize(2_000);
            assertThat(refunds).hasSize(2_000);
            assertThat(sim.stripe().ledger().size()).isEqualTo(2_000);
        }

        @Test
        @DisplayName("Adyen: pspReference de 16 dígitos en pagos y modificaciones, todos distintos, desde 8 hilos")
        void adyenFormatsAndUniqueness() throws Exception {
            List<String[]> refs = hammer(SimulatedGatewayCatalog.ADYEN, 250);

            Set<String> all = new HashSet<>();
            for (String[] r : refs) {
                for (String ref : r) {
                    assertThat(ref).matches(ADYEN_PSP);
                    all.add(ref);
                }
            }
            assertThat(all).as("pago, captura y reembolso tienen cada uno su pspReference").hasSize(6_000);
        }

        @Test
        @DisplayName("Redsys: Ds_Order de 12 (4 dígitos + 8 alfanuméricos) único; devolución = Ds_Order-código de 6 dígitos")
        void redsysFormatsAndUniqueness() throws Exception {
            List<String[]> refs = hammer(SimulatedGatewayCatalog.REDSYS, 250);

            Set<String> orders = new HashSet<>();
            for (String[] r : refs) {
                assertThat(r[0]).hasSize(12).matches(REDSYS_ORDER);
                assertThat(r[2]).isEqualTo(r[0]);
                assertThat(r[1]).startsWith(r[0] + "-");
                assertThat(r[1].substring(13)).matches(REDSYS_AUTH_CODE);
                orders.add(r[0]);
            }
            assertThat(orders).hasSize(2_000);
        }

        @Test
        @DisplayName("Redsys: Ds_AuthorisationCode de 6 dígitos en preautorización, confirmación y devolución; no se repite en un pedido")
        void redsysAuthorisationCodes() {
            RedsysClient client = new RedsysClient(new Random(3));
            Set<String> codes = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                String order = "%04dABCD%04d".formatted(i, i);
                RedsysResponse pre = client.process(new RedsysRequest(order, "10000", "978", "1", "999008881", "1"));
                RedsysResponse conf = client.process(new RedsysRequest(order, "10000", "978", "2", "999008881", "1"));
                assertThat(pre.dsResponse()).isEqualTo("0000");
                assertThat(conf.dsResponse()).isEqualTo("0900");
                Set<String> inOrder = new HashSet<>();
                inOrder.add(pre.dsAuthorisationCode());
                inOrder.add(conf.dsAuthorisationCode());
                for (int k = 0; k < 10; k++) {
                    RedsysResponse dev = client.process(new RedsysRequest(order, "100", "978", "3", "999008881", "1"));
                    assertThat(dev.dsResponse()).isEqualTo("0900");
                    inOrder.add(dev.dsAuthorisationCode());
                }
                assertThat(inOrder).as("12 operaciones del pedido %s, 12 códigos distintos", order).hasSize(12)
                        .allSatisfy(c -> assertThat(c).matches(REDSYS_AUTH_CODE));
                codes.addAll(inOrder);
            }
            assertThat(codes).isNotEmpty();
        }

        @Test
        @DisplayName("mock: referencias mock_NNNNNN únicas con 8 hilos")
        void mockUniqueness() throws Exception {
            List<String[]> refs = hammer(SimulatedGatewayCatalog.MOCK, 250);
            Set<String> auths = new HashSet<>();
            Set<String> refunds = new HashSet<>();
            for (String[] r : refs) {
                assertThat(r[0]).matches("mock_[0-9]{6,}");
                auths.add(r[0]);
                refunds.add(r[1]);
            }
            assertThat(auths).hasSize(2_000);
            assertThat(refunds).hasSize(2_000);
        }
    }

    // ---------------------------------------------------------------------------------
    // Regla del 51
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("regla del 51: % 100 == 51 → DECLINED con el código nativo; cualquier otro → APPROVED")
    void rule51(GatewayId id) {
        Random random = new Random(id.value().hashCode());
        List<Long> amounts = new ArrayList<>(List.of(1L, 50L, 51L, 52L, 99L, 100L, 101L, 151L, 251L, 5_151L, 10_051L,
                10_050L, 10_052L, 510L, 5_100L, 999_999_999_951L, 1_000_000_000_000L, Long.MAX_VALUE,
                Long.MAX_VALUE - 56));
        for (int i = 0; i < 1_500; i++) {
            amounts.add(1 + (long) (random.nextDouble() * 1_000_000_000L));
        }
        int declined = 0;
        for (long amount : amounts) {
            GatewayResponse r = gw(id).authorize(SimFixtures.authorization(amount));
            if (amount % 100 == 51) {
                declined++;
                assertThat(r.outcome()).as("%s con %d", id, amount).isEqualTo(AttemptOutcome.DECLINED);
                assertThat(r.code()).as("%s con %d", id, amount).contains(DECLINE_CODE.get(id));
                assertThat(r.gatewayReference()).isEmpty();
            } else {
                assertThat(r.outcome()).as("%s con %d: %s", id, amount, r).isEqualTo(AttemptOutcome.APPROVED);
            }
        }
        assertThat(declined).isGreaterThanOrEqualTo(4);
        // Un rechazo no deja autorización en el libro.
        assertThat(ledger(id).size()).isEqualTo(amounts.size() - declined);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("importes 0 o negativos → DECLINED, nunca ERROR ni excepción, y el libro no cambia")
    void zeroAndNegativeAmounts(GatewayId id) {
        for (long amount : new long[] {0, -1, -51, -10_000, Long.MIN_VALUE}) {
            GatewayResponse r = gw(id).authorize(SimFixtures.authorization(amount));
            assertDeclined(r, id + " autoriza " + amount);
        }
        assertThat(ledger(id).size()).isZero();
    }

    @Test
    @DisplayName("regla del 51 en JPY (sin decimales): 151 JPY rechazado en adyen y mock; 150 JPY aprobado")
    void rule51InJpy() {
        for (GatewayId id : List.of(SimulatedGatewayCatalog.ADYEN, SimulatedGatewayCatalog.MOCK)) {
            assertThat(gw(id).authorize(SimFixtures.authorization(151, Currency.JPY)).outcome())
                    .isEqualTo(AttemptOutcome.DECLINED);
            assertThat(gw(id).authorize(SimFixtures.authorization(150, Currency.JPY)).outcome())
                    .isEqualTo(AttemptOutcome.APPROVED);
        }
    }

    // ---------------------------------------------------------------------------------
    // El libro de la PSP: lo que no admite es DECLINED, nunca ERROR
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("libro: importe que no cuadra, reembolso sin captura, doble captura, anular capturado, reembolsar de más → DECLINED")
    void ledgerRejectionsAreDeclines(GatewayId id) {
        PaymentGateway g = gw(id);
        String ref = authorized(id, 10_000);

        assertDeclined(g.capture(ref, eur(9_999)), "capturar con otro importe");
        assertDeclined(g.capture(ref, eur(10_001)), "capturar de más");
        assertDeclined(g.refund(ref, eur(100)), "reembolsar sin capturar");
        assertThat(ledger(id).find(ref).orElseThrow().state()).isEqualTo(SimulatedLedger.State.AUTHORIZED);

        assertThat(g.capture(ref, eur(10_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertDeclined(g.capture(ref, eur(10_000)), "doble captura");
        assertDeclined(g.cancel(ref), "anular después de capturar");

        // Reembolsos parciales que suman exacto, cada uno con su referencia.
        Set<String> refundRefs = new HashSet<>();
        for (long part : new long[] {3_000, 3_000, 3_999, 1}) {
            GatewayResponse r = g.refund(ref, eur(part));
            assertThat(r.outcome()).as("reembolso parcial de %d", part).isEqualTo(AttemptOutcome.APPROVED);
            refundRefs.add(r.gatewayReference().orElseThrow());
        }
        assertThat(refundRefs).hasSize(4);
        SimulatedLedger.Entry entry = ledger(id).find(ref).orElseThrow();
        assertThat(entry.refundedMinor()).isEqualTo(10_000);
        assertThat(entry.refundCount()).isEqualTo(4);

        assertDeclined(g.refund(ref, eur(1)), "reembolsar un céntimo de más");
        assertDeclined(g.refund(ref, eur(Long.MAX_VALUE)), "reembolso gigantesco");
        assertDeclined(g.refund(ref, eur(0)), "reembolso de 0");
        assertDeclined(g.refund(ref, eur(-100)), "reembolso negativo");
        assertThat(ledger(id).find(ref).orElseThrow()).isEqualTo(entry);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("libro: reembolsar de más de golpe y en otra divisa → DECLINED y no cuenta nada")
    void overRefundAndCurrencyMismatch(GatewayId id) {
        PaymentGateway g = gw(id);
        String ref = authorized(id, 10_000);
        assertThat(g.capture(ref, eur(10_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);

        assertDeclined(g.refund(ref, eur(10_001)), "reembolsar de más");
        assertDeclined(g.refund(ref, Money.of(100, Currency.USD)), "reembolsar en otra divisa");
        assertThat(ledger(id).find(ref).orElseThrow().refundedMinor()).isZero();
        assertThat(g.refund(ref, eur(10_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("libro: anular y después capturar, anular dos veces o reembolsar lo anulado → DECLINED")
    void cancelFlow(GatewayId id) {
        PaymentGateway g = gw(id);
        String ref = authorized(id, 10_000);

        assertThat(g.cancel(ref).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertDeclined(g.cancel(ref), "anular dos veces");
        assertDeclined(g.capture(ref, eur(10_000)), "capturar lo anulado");
        assertDeclined(g.refund(ref, eur(100)), "reembolsar lo anulado");
        assertThat(ledger(id).find(ref).orElseThrow().state()).isEqualTo(SimulatedLedger.State.CANCELED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("libro: referencias desconocidas, vacías o con formato raro → DECLINED en capturar, reembolsar y anular")
    void unknownReferences(GatewayId id) {
        PaymentGateway g = gw(id);
        for (String ref : List.of("desconocida", "1234ABCDEFGH", "pi_000000000000000000000000", "1234567890123456",
                "mock_999999", "", " ")) {
            assertDeclined(g.capture(ref, eur(100)), id + " capture " + ref);
            assertDeclined(g.refund(ref, eur(100)), id + " refund " + ref);
            assertDeclined(g.cancel(ref), id + " cancel " + ref);
        }
        assertThat(ledger(id).size()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("16 capturas simultáneas de la misma autorización por el puerto: 1 APPROVED y 15 DECLINED, ningún ERROR")
    void concurrentCapturesThroughThePort(GatewayId id) throws Exception {
        String ref = authorized(id, 10_000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<GatewayResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return gw(id).capture(ref, eur(10_000));
                }));
            }
            start.countDown();
            List<AttemptOutcome> outcomes = new ArrayList<>();
            for (Future<GatewayResponse> f : futures) {
                outcomes.add(f.get(10, TimeUnit.SECONDS).outcome());
            }
            assertThat(outcomes).filteredOn(o -> o == AttemptOutcome.APPROVED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == AttemptOutcome.DECLINED).hasSize(15);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------
    // Averías
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("con avería (down, errorRate 1, timeout) ninguna operación toca el libro; al quitarla todo funciona")
    void faultsNeverTouchTheLedger(GatewayId id) {
        PaymentGateway g = gw(id);
        String ref = authorized(id, 10_000);
        String captured = authorized(id, 5_000);
        assertThat(g.capture(captured, eur(5_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        int sizeBefore = ledger(id).size();
        SimulatedLedger.Entry refBefore = ledger(id).find(ref).orElseThrow();
        SimulatedLedger.Entry capturedBefore = ledger(id).find(captured).orElseThrow();

        List<FaultConfig> faults = List.of(
                FaultConfig.none().withDown(true),
                FaultConfig.none().withErrorRate(1.0),
                FaultConfig.none().withLatency(Duration.ofSeconds(3)));
        for (FaultConfig fault : faults) {
            sim.chaosControl().set(id, fault);
            List<GatewayResponse> responses = List.of(
                    g.authorize(SimFixtures.authorization(7_000)),
                    g.capture(ref, eur(10_000)),
                    g.cancel(ref),
                    g.refund(captured, eur(100)));
            for (GatewayResponse r : responses) {
                assertThat(r.outcome()).as("%s con %s", id, fault)
                        .isIn(AttemptOutcome.ERROR, AttemptOutcome.TIMEOUT);
                assertThat(r.gatewayReference()).isEmpty();
            }
            assertThat(ledger(id).size()).isEqualTo(sizeBefore);
            assertThat(ledger(id).find(ref).orElseThrow()).isEqualTo(refBefore);
            assertThat(ledger(id).find(captured).orElseThrow()).isEqualTo(capturedBefore);
        }

        sim.chaosControl().reset(id);
        assertThat(g.capture(ref, eur(10_000)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(g.refund(captured, eur(100)).outcome()).isEqualTo(AttemptOutcome.APPROVED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allGateways")
    @DisplayName("down → ERROR HTTP_503; errorRate 1 → ERROR HTTP_500")
    void downAndErrorCodes(GatewayId id) {
        sim.chaosControl().set(id, FaultConfig.none().withDown(true));
        GatewayResponse down = gw(id).authorize(SimFixtures.authorization(10_000));
        assertThat(down.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(down.code()).contains(FaultInjector.HTTP_503);

        sim.chaosControl().set(id, FaultConfig.none().withErrorRate(1.0));
        GatewayResponse error = gw(id).authorize(SimFixtures.authorization(10_000));
        assertThat(error.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(error.code()).contains(FaultInjector.HTTP_500);
    }

    @Nested
    @DisplayName("latencia y timeout (2 s por defecto)")
    class Latency {

        @Test
        @DisplayName("por debajo del timeout: APPROVED con esa latencia y se duerme exactamente eso")
        void belowTimeout() {
            sim.chaosControl().set(SimulatedGatewayCatalog.STRIPE,
                    FaultConfig.none().withLatency(Duration.ofMillis(1_999)));

            GatewayResponse r = gw(SimulatedGatewayCatalog.STRIPE).authorize(SimFixtures.authorization(10_000));

            assertThat(r.outcome()).isEqualTo(AttemptOutcome.APPROVED);
            assertThat(r.latency()).isEqualTo(Duration.ofMillis(1_999));
            assertThat(sleeper.sleeps()).containsExactly(Duration.ofMillis(1_999));
        }

        @Test
        @DisplayName("por encima: TIMEOUT con latencia = timeout, y el Sleeper solo duerme el timeout")
        void aboveTimeout() {
            sim.chaosControl().set(SimulatedGatewayCatalog.REDSYS,
                    FaultConfig.none().withLatency(Duration.ofHours(1)));

            GatewayResponse r = gw(SimulatedGatewayCatalog.REDSYS).authorize(SimFixtures.authorization(10_000));

            assertThat(r.outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
            assertThat(r.latency()).isEqualTo(FaultInjector.DEFAULT_TIMEOUT);
            assertThat(sleeper.sleeps()).containsExactly(FaultInjector.DEFAULT_TIMEOUT);
            assertThat(sim.redsys().ledger().size()).isZero();
        }

        @Test
        @DisplayName("en el borde: latencia == timeout llega a tiempo (APPROVED); 1 ns más ya es TIMEOUT (documentado: «si supera»)")
        void exactlyAtTimeout() {
            sim.chaosControl().set(SimulatedGatewayCatalog.ADYEN,
                    FaultConfig.none().withLatency(FaultInjector.DEFAULT_TIMEOUT));
            GatewayResponse atEdge = gw(SimulatedGatewayCatalog.ADYEN).authorize(SimFixtures.authorization(10_000));
            assertThat(atEdge.outcome()).isEqualTo(AttemptOutcome.APPROVED);
            assertThat(atEdge.latency()).isEqualTo(FaultInjector.DEFAULT_TIMEOUT);

            sim.chaosControl().set(SimulatedGatewayCatalog.ADYEN,
                    FaultConfig.none().withLatency(FaultInjector.DEFAULT_TIMEOUT.plusNanos(1)));
            GatewayResponse past = gw(SimulatedGatewayCatalog.ADYEN).authorize(SimFixtures.authorization(10_000));
            assertThat(past.outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
            assertThat(past.latency()).isEqualTo(FaultInjector.DEFAULT_TIMEOUT);
            assertThat(sleeper.sleeps()).containsExactly(FaultInjector.DEFAULT_TIMEOUT, FaultInjector.DEFAULT_TIMEOUT);
        }

        @Test
        @DisplayName("un rechazo del 51 con latencia conserva el DECLINED y la latencia simulada")
        void declineKeepsLatency() {
            sim.chaosControl().set(SimulatedGatewayCatalog.MOCK, FaultConfig.none().withLatency(Duration.ofMillis(300)));
            GatewayResponse r = gw(SimulatedGatewayCatalog.MOCK).authorize(SimFixtures.authorization(10_051));
            assertThat(r.outcome()).isEqualTo(AttemptOutcome.DECLINED);
            assertThat(r.latency()).isEqualTo(Duration.ofMillis(300));
        }
    }

    @Nested
    @DisplayName("errorRate con un Random falso")
    class ErrorRate {

        /** Random que siempre devuelve el mismo double. */
        private static Random constant(double value) {
            return new Random() {
                @Override
                public double nextDouble() {
                    return value;
                }
            };
        }

        @Test
        @DisplayName("errorRate 0 nunca falla, aunque el aleatorio dé 0.0")
        void zeroNeverFails() {
            FaultInjector injector = new FaultInjector(constant(0.0), new RecordingSleeper());
            injector.configure(FaultConfig.none().withErrorRate(0.0));
            for (int i = 0; i < 1_000; i++) {
                assertThat(injector.call(() -> GatewayResponse.approved("x", Duration.ZERO)).outcome())
                        .isEqualTo(AttemptOutcome.APPROVED);
            }
        }

        @Test
        @DisplayName("errorRate 1 siempre falla, aunque el aleatorio dé el mayor double < 1")
        void oneAlwaysFails() {
            FaultInjector injector = new FaultInjector(constant(Math.nextDown(1.0)), new RecordingSleeper());
            injector.configure(FaultConfig.none().withErrorRate(1.0));
            AtomicBoolean called = new AtomicBoolean();
            for (int i = 0; i < 1_000; i++) {
                GatewayResponse r = injector.call(() -> {
                    called.set(true);
                    return GatewayResponse.approved("x", Duration.ZERO);
                });
                assertThat(r.outcome()).isEqualTo(AttemptOutcome.ERROR);
                assertThat(r.code()).contains(FaultInjector.HTTP_500);
            }
            assertThat(called).as("con error, la PSP no se llega a llamar").isFalse();
        }

        @Test
        @DisplayName("errorRate fuera de [0,1] o NaN no se admite")
        void invalidRates() {
            for (double bad : new double[] {-0.0001, 1.0001, Double.NaN, Double.POSITIVE_INFINITY}) {
                assertThatThrownBy(() -> FaultConfig.none().withErrorRate(bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> FaultConfig.none().withLatency(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("thread-safety: 8 hilos operan mientras otro cambia la avería sin parar; ninguna excepción ni respuesta rara")
    void reconfigureWhileCalling() throws Exception {
        GatewaySimulator concurrent = GatewaySimulator.create(new Random(11), new RecordingSleeper());
        GatewayChaosControl chaos = concurrent.chaosControl();
        List<FaultConfig> configs = List.of(FaultConfig.none(), FaultConfig.none().withDown(true),
                FaultConfig.none().withErrorRate(0.5), FaultConfig.none().withLatency(Duration.ofSeconds(5)),
                FaultConfig.none().withLatency(Duration.ofMillis(10)).withErrorRate(0.2));
        AtomicBoolean stop = new AtomicBoolean();
        Set<String> codes = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(9);
        try {
            Future<?> flipper = pool.submit(() -> {
                Random r = new Random(5);
                while (!stop.get()) {
                    GatewayId target = allGateways().toList().get(r.nextInt(4));
                    chaos.set(target, configs.get(r.nextInt(configs.size())));
                    if (r.nextInt(50) == 0) {
                        chaos.resetAll();
                    }
                }
            });
            List<Future<Map<GatewayId, Integer>>> workers = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                workers.add(pool.submit(() -> {
                    Map<GatewayId, Integer> approvedAuths = new java.util.HashMap<>();
                    for (int i = 0; i < 400; i++) {
                        for (GatewayId id : allGateways().toList()) {
                            PaymentGateway g = concurrent.catalog().gateway(id).orElseThrow();
                            GatewayResponse auth = g.authorize(SimFixtures.authorization(10_000));
                            auth.code().ifPresent(codes::add);
                            if (auth.isApproved()) {
                                approvedAuths.merge(id, 1, Integer::sum);
                                g.capture(auth.gatewayReference().orElseThrow(), eur(10_000)).code()
                                        .ifPresent(codes::add);
                            }
                        }
                    }
                    return approvedAuths;
                }));
            }
            Map<GatewayId, Integer> totals = new java.util.HashMap<>();
            for (Future<Map<GatewayId, Integer>> w : workers) {
                w.get(120, TimeUnit.SECONDS).forEach((k, v) -> totals.merge(k, v, Integer::sum));
            }
            stop.set(true);
            flipper.get(10, TimeUnit.SECONDS);

            assertThat(codes).as("solo los códigos de avería esperados")
                    .isSubsetOf(FaultInjector.HTTP_503, FaultInjector.HTTP_500);
            // Cada autorización aprobada está en el libro, y solo esas.
            assertThat(concurrent.stripe().ledger().size()).isEqualTo(totals.getOrDefault(SimulatedGatewayCatalog.STRIPE, 0));
            assertThat(concurrent.adyen().ledger().size()).isEqualTo(totals.getOrDefault(SimulatedGatewayCatalog.ADYEN, 0));
            assertThat(concurrent.redsys().ledger().size()).isEqualTo(totals.getOrDefault(SimulatedGatewayCatalog.REDSYS, 0));
            assertThat(concurrent.mock().ledger().size()).isEqualTo(totals.getOrDefault(SimulatedGatewayCatalog.MOCK, 0));
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------
    // ChaosControl y catálogo
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("ChaosControl: set, reset, resetAll y snapshot (copia inmodificable que no cambia después)")
    void chaosControl() {
        GatewayChaosControl chaos = sim.chaosControl();
        FaultConfig down = FaultConfig.none().withDown(true);
        FaultConfig slow = FaultConfig.none().withLatency(Duration.ofMillis(500)).withErrorRate(0.25);

        chaos.set(SimulatedGatewayCatalog.STRIPE, down);
        chaos.set(SimulatedGatewayCatalog.MOCK, slow);
        Map<GatewayId, FaultConfig> snapshot = chaos.snapshot();

        assertThat(snapshot.keySet()).containsExactly(SimulatedGatewayCatalog.STRIPE, SimulatedGatewayCatalog.ADYEN,
                SimulatedGatewayCatalog.REDSYS, SimulatedGatewayCatalog.MOCK);
        assertThat(snapshot.get(SimulatedGatewayCatalog.STRIPE)).isEqualTo(down);
        assertThat(snapshot.get(SimulatedGatewayCatalog.MOCK)).isEqualTo(slow);
        assertThat(snapshot.get(SimulatedGatewayCatalog.ADYEN)).isEqualTo(FaultConfig.none());
        assertThatThrownBy(() -> snapshot.put(SimulatedGatewayCatalog.ADYEN, down))
                .isInstanceOf(UnsupportedOperationException.class);

        chaos.reset(SimulatedGatewayCatalog.STRIPE);
        assertThat(snapshot.get(SimulatedGatewayCatalog.STRIPE)).as("el snapshot es una foto fija").isEqualTo(down);
        assertThat(chaos.snapshot().get(SimulatedGatewayCatalog.STRIPE)).isEqualTo(FaultConfig.none());
        assertThat(chaos.snapshot().get(SimulatedGatewayCatalog.MOCK)).isEqualTo(slow);

        chaos.set(SimulatedGatewayCatalog.ADYEN, down);
        chaos.set(SimulatedGatewayCatalog.REDSYS, down);
        chaos.resetAll();
        assertThat(chaos.snapshot().values()).containsOnly(FaultConfig.none());

        assertThatThrownBy(() -> chaos.set(GatewayId.of("paypal"), down)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chaos.reset(GatewayId.of("paypal"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chaos.set(null, down)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chaos.set(SimulatedGatewayCatalog.STRIPE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("catálogo: perfiles exactos de stripe, adyen, redsys y mock, en ese orden")
    void exactProfiles() {
        List<GatewayProfile> profiles = sim.catalog().profiles();
        assertThat(profiles).extracting(GatewayProfile::id).containsExactly(SimulatedGatewayCatalog.STRIPE,
                SimulatedGatewayCatalog.ADYEN, SimulatedGatewayCatalog.REDSYS, SimulatedGatewayCatalog.MOCK);

        GatewayProfile stripe = profiles.get(0);
        assertThat(stripe.currencies()).containsExactlyInAnyOrder(Currency.EUR, Currency.USD, Currency.GBP);
        assertThat(stripe.countries()).isEmpty();
        assertThat(stripe.feeBasisPoints()).isEqualTo(290);
        assertThat(stripe.fixedFeeMinor()).isEqualTo(Map.of(Currency.EUR, 25L, Currency.USD, 25L, Currency.GBP, 25L));

        GatewayProfile adyen = profiles.get(1);
        assertThat(adyen.currencies()).containsExactlyInAnyOrder(Currency.EUR, Currency.USD, Currency.GBP,
                Currency.JPY);
        assertThat(adyen.countries()).isEmpty();
        assertThat(adyen.feeBasisPoints()).isEqualTo(250);
        assertThat(adyen.fixedFeeMinor())
                .isEqualTo(Map.of(Currency.EUR, 30L, Currency.USD, 30L, Currency.GBP, 30L, Currency.JPY, 0L));

        GatewayProfile redsys = profiles.get(2);
        assertThat(redsys.currencies()).containsExactly(Currency.EUR);
        assertThat(redsys.countries()).containsExactly(CountryCode.of("ES"));
        assertThat(redsys.feeBasisPoints()).isEqualTo(120);
        assertThat(redsys.fixedFeeMinor().values()).allMatch(v -> v == 0L);

        GatewayProfile mock = profiles.get(3);
        assertThat(mock.currencies()).isEqualTo(EnumSet.allOf(Currency.class));
        assertThat(mock.countries()).isEmpty();
        assertThat(mock.feeBasisPoints()).isEqualTo(900);
        assertThat(mock.fixedFeeMinor().values()).allMatch(v -> v == 0L);

        assertThat(profiles).allMatch(GatewayProfile::enabled);
        assertThatThrownBy(() -> profiles.add(stripe)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("catálogo: setEnabled saca y mete; la deshabilitada conserva su adaptador; una desconocida es un error")
    void setEnabled() {
        SimulatedGatewayCatalog catalog = sim.catalog();
        String ref = authorized(SimulatedGatewayCatalog.REDSYS, 10_000);

        catalog.setEnabled(SimulatedGatewayCatalog.REDSYS, false);
        assertThat(catalog.profiles()).filteredOn(p -> p.id().equals(SimulatedGatewayCatalog.REDSYS))
                .singleElement().satisfies(p -> assertThat(p.enabled()).isFalse());
        assertThat(catalog.profiles()).filteredOn(p -> !p.id().equals(SimulatedGatewayCatalog.REDSYS))
                .allMatch(GatewayProfile::enabled);
        assertThat(catalog.gateway(SimulatedGatewayCatalog.REDSYS)).isPresent();
        assertThat(gw(SimulatedGatewayCatalog.REDSYS).capture(ref, eur(10_000)).outcome())
                .isEqualTo(AttemptOutcome.APPROVED);

        catalog.setEnabled(SimulatedGatewayCatalog.REDSYS, true);
        assertThat(catalog.profiles()).allMatch(GatewayProfile::enabled);
        assertThatThrownBy(() -> catalog.setEnabled(GatewayId.of("paypal"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.gateway(GatewayId.of("paypal"))).isEmpty();
    }
}
