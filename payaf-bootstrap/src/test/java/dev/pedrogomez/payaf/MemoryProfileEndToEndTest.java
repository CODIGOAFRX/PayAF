package dev.pedrogomez.payaf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultConfig;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewayChaosControl;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewaySimulator;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger;
import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CancelPaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.in.ReapReport;
import dev.pedrogomez.payaf.application.port.in.ReapStalePaymentsUseCase;
import dev.pedrogomez.payaf.application.port.in.RefundPaymentUseCase;
import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.application.service.AuthorizePaymentService;
import dev.pedrogomez.payaf.config.ReaperScheduler;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import dev.pedrogomez.payaf.domain.event.PaymentCanceled;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.event.PaymentEvent;
import dev.pedrogomez.payaf.domain.event.PaymentRefunded;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Tester (fase 4a): el hexágono completo en Spring con el perfil memory, de punta a punta y con
 * un reloj controlable (el bean {@code clock} se sustituye con {@code @TestBean}).
 * <p>
 * Cada test arranca un contexto limpio ({@code AFTER_EACH_TEST_METHOD}): los breakers y el libro
 * de las PSP son estado compartido del contexto, y un fallo provocado en un test abriría el
 * breaker en otro. El reaper programado se apaga ({@code payaf.reaper.enabled=false}) para que no
 * se cruce con los saltos del reloj; su cableado se comprueba en {@code ReaperSchedulerWiringTest}.
 */
@SpringBootTest(properties = "payaf.reaper.enabled=false")
@ActiveProfiles("memory")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MemoryProfileEndToEndTest {

    private static final GatewayId STRIPE = GatewayId.of("stripe");
    private static final GatewayId ADYEN = GatewayId.of("adyen");
    private static final GatewayId REDSYS = GatewayId.of("redsys");
    private static final GatewayId MOCK = GatewayId.of("mock");

    @TestBean
    private Clock clock;

    static Clock clock() {
        return new MutableTestClock(Instant.parse("2026-11-27T10:00:00Z"));
    }

    @Autowired
    private AuthorizePaymentUseCase authorize;
    @Autowired
    private CapturePaymentUseCase capture;
    @Autowired
    private RefundPaymentUseCase refund;
    @Autowired
    private CancelPaymentUseCase cancel;
    @Autowired
    private ReapStalePaymentsUseCase reaper;
    @Autowired
    private GatewayChaosControl chaos;
    @Autowired
    private GatewaySimulator simulator;
    @Autowired
    private CircuitBreakerStore breakers;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private IdempotencyStore idempotency;
    @Autowired
    private TransactionRunner transactions;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private dev.pedrogomez.payaf.adapter.out.persistencememory.InMemoryEventPublisher events;

    private MutableTestClock testClock() {
        return (MutableTestClock) clock;
    }

    private static PaymentRequest request(String key, long minor, Currency currency, String country) {
        return new PaymentRequest(Money.of(minor, currency), CountryCode.of(country), "pedido-" + key,
                IdempotencyKey.of(key));
    }

    private AuthorizationResult pay(long minor) {
        return pay(minor, Currency.EUR, "ES");
    }

    private AuthorizationResult pay(long minor, Currency currency, String country) {
        String key = "e2e-" + UUID.randomUUID();
        return authorize.authorize(new AuthorizePaymentCommand(request(key, minor, currency, country)));
    }

    private void down(GatewayId... ids) {
        for (GatewayId id : ids) {
            chaos.set(id, FaultConfig.none().withDown(true));
        }
    }

    private static List<GatewayId> attempted(AuthorizationResult result) {
        return result.attempts().stream().map(GatewayAttempt::gateway).toList();
    }

    // ---------------------------------------------------------------------------------
    // Enrutado y failover
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("el bean clock es el del test: el reloj del contexto se puede controlar")
    void clockIsReplaced() {
        assertThat(context.getBean(Clock.class)).isSameAs(clock).isInstanceOf(MutableTestClock.class);
        AuthorizationResult result = pay(10_000);
        assertThat(result.payment().createdAt()).isEqualTo(Instant.parse("2026-11-27T10:00:00Z"));
    }

    @Test
    @DisplayName("100 € ES → redsys")
    void routesToRedsys() {
        AuthorizationResult result = pay(10_000);
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.payment().authorizedBy()).contains(REDSYS);
        assertThat(attempted(result)).containsExactly(REDSYS);
    }

    @Test
    @DisplayName("redsys down → adyen")
    void redsysDownGoesToAdyen() {
        down(REDSYS);
        AuthorizationResult result = pay(10_000);
        assertThat(result.payment().authorizedBy()).contains(ADYEN);
        assertThat(result.attempts()).extracting(GatewayAttempt::gateway, GatewayAttempt::outcome)
                .containsExactly(tuple(REDSYS, AttemptOutcome.ERROR), tuple(ADYEN, AttemptOutcome.APPROVED));
        assertThat(result.payment().gatewayReference().orElseThrow()).matches("[0-9]{16}");
    }

    @Test
    @DisplayName("adyen y redsys down → stripe")
    void redsysAndAdyenDownGoesToStripe() {
        down(REDSYS, ADYEN);
        AuthorizationResult result = pay(10_000);
        assertThat(result.payment().authorizedBy()).contains(STRIPE);
        assertThat(attempted(result)).containsExactly(REDSYS, ADYEN, STRIPE);
        assertThat(result.payment().gatewayReference().orElseThrow()).matches("pi_[A-Za-z0-9]{24}");
    }

    @Test
    @DisplayName("todas down salvo mock → mock")
    void onlyMockUpGoesToMock() {
        down(REDSYS, ADYEN, STRIPE);
        AuthorizationResult result = pay(10_000);
        assertThat(result.payment().authorizedBy()).contains(MOCK);
        assertThat(attempted(result)).containsExactly(REDSYS, ADYEN, STRIPE, MOCK);
    }

    @Test
    @DisplayName("todas down → FAILED NO_GATEWAY_AVAILABLE, la clave queda completada y el reintento es un replay")
    void allDownFails() {
        down(REDSYS, ADYEN, STRIPE, MOCK);
        PaymentRequest req = request("all-down", 10_000, Currency.EUR, "ES");

        AuthorizationResult result = authorize.authorize(new AuthorizePaymentCommand(req));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.NO_GATEWAY_AVAILABLE);
        assertThat(result.attempts()).extracting(GatewayAttempt::outcome).containsOnly(AttemptOutcome.ERROR)
                .hasSize(4);
        assertThat(result.attempts()).allSatisfy(a -> assertThat(a.code()).contains(FaultInjector.HTTP_503));
        // Ninguna PSP ha apuntado nada.
        assertThat(simulator.redsys().ledger().size() + simulator.adyen().ledger().size()
                + simulator.stripe().ledger().size() + simulator.mock().ledger().size()).isZero();

        chaos.resetAll();
        AuthorizationResult replay = authorize.authorize(new AuthorizePaymentCommand(req));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("USD va a adyen y JPY también (stripe es más cara y redsys solo EUR/ES); EUR fuera de España no usa redsys")
    void currencyAndCountryRules() {
        assertThat(pay(10_000, Currency.USD, "US").payment().authorizedBy()).contains(ADYEN);
        assertThat(pay(10_000, Currency.JPY, "JP").payment().authorizedBy()).contains(ADYEN);
        AuthorizationResult france = pay(10_000, Currency.EUR, "FR");
        assertThat(france.decision().candidates()).extracting(c -> c.gateway())
                .doesNotContain(REDSYS);
        assertThat(france.payment().authorizedBy()).contains(ADYEN);
    }

    // ---------------------------------------------------------------------------------
    // Circuit breaker de redsys (failure-threshold: 3, open-duration: 15s)
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("3 fallos seguidos abren redsys; la siguiente ni lo intenta; a los 15 s prueba (HALF_OPEN) y se recupera")
    void redsysBreakerOpensAndRecovers() {
        down(REDSYS);
        for (int i = 0; i < 3; i++) {
            AuthorizationResult r = pay(10_000);
            assertThat(attempted(r)).as("pago %d", i + 1).containsExactly(REDSYS, ADYEN);
            testClock().advance(Duration.ofSeconds(1));
        }
        assertThat(breakers.get(REDSYS).state()).isEqualTo(CircuitState.OPEN);

        // Con el circuito abierto, redsys no se intenta aunque ya esté levantada.
        chaos.resetAll();
        AuthorizationResult skipped = pay(10_000);
        assertThat(attempted(skipped)).containsExactly(ADYEN);
        int redsysLedger = simulator.redsys().ledger().size();

        // Se abrió con el tercer fallo (t0 + 2 s) y el reloj va por t0 + 3 s. Un milisegundo
        // antes de los 15 s sigue abierto.
        testClock().advance(Duration.ofSeconds(13).plusMillis(999));
        assertThat(attempted(pay(10_000))).containsExactly(ADYEN);

        // A los 15 s justos desde la apertura: prueba en HALF_OPEN, sale bien y se cierra.
        testClock().advance(Duration.ofMillis(1));
        AuthorizationResult trial = pay(10_000);
        assertThat(attempted(trial)).containsExactly(REDSYS);
        assertThat(trial.payment().authorizedBy()).contains(REDSYS);
        assertThat(breakers.get(REDSYS).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(simulator.redsys().ledger().size()).isEqualTo(redsysLedger + 1);
        assertThat(attempted(pay(10_000))).containsExactly(REDSYS);
    }

    @Test
    @DisplayName("HALF_OPEN con redsys aún caída: la prueba falla, se vuelve a abrir y la siguiente no lo intenta")
    void halfOpenTrialFailsReopens() {
        down(REDSYS);
        for (int i = 0; i < 3; i++) {
            pay(10_000);
        }
        assertThat(breakers.get(REDSYS).state()).isEqualTo(CircuitState.OPEN);
        testClock().advance(Duration.ofSeconds(15));

        AuthorizationResult trial = pay(10_000);
        assertThat(trial.attempts()).extracting(GatewayAttempt::gateway, GatewayAttempt::outcome)
                .containsExactly(tuple(REDSYS, AttemptOutcome.ERROR), tuple(ADYEN, AttemptOutcome.APPROVED));
        assertThat(breakers.get(REDSYS).state()).isEqualTo(CircuitState.OPEN);
        assertThat(attempted(pay(10_000))).containsExactly(ADYEN);
    }

    @Test
    @DisplayName("un rechazo del 51 no cuenta como fallo: 5 rechazos seguidos no abren el breaker")
    void declinesDoNotOpenTheBreaker() {
        for (int i = 0; i < 5; i++) {
            pay(10_051);
        }
        assertThat(breakers.get(REDSYS).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(attempted(pay(10_000))).containsExactly(REDSYS);
    }

    @Test
    @DisplayName("un DECLINED del libro de la PSP (capturar lo ya capturado en redsys) no cuenta para el breaker")
    void ledgerDeclinesDoNotOpenTheBreaker() {
        for (int i = 0; i < 4; i++) {
            PaymentView authorized = pay(10_000).payment();
            String ref = authorized.gatewayReference().orElseThrow();
            // Alguien la captura directamente en la PSP; el pago aún no lo sabe.
            simulator.catalog().gateway(REDSYS).orElseThrow().capture(ref, Money.of(10_000, Currency.EUR));

            assertThatThrownBy(() -> capture.capture(authorized.id()))
                    .isInstanceOfSatisfying(ApplicationException.class, e -> {
                        assertThat(e.code()).isEqualTo(ApplicationException.GATEWAY_OPERATION_FAILED);
                        assertThat(e.getMessage()).contains("SIS0063");
                    });
        }
        assertThat(breakers.get(REDSYS).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(attempted(pay(10_000))).containsExactly(REDSYS);
    }

    // ---------------------------------------------------------------------------------
    // Regla del 51
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("regla del 51: FAILED DECLINED con el código nativo de la PSP elegida y sin failover")
    void rule51EndToEnd() {
        AuthorizationResult redsys = pay(10_051);
        assertThat(redsys.payment().failureReason()).contains("DECLINED: 0190");
        assertThat(attempted(redsys)).containsExactly(REDSYS);

        down(REDSYS);
        AuthorizationResult adyen = pay(10_051);
        assertThat(adyen.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(adyen.payment().failureReason()).contains("DECLINED: Not enough balance");
        assertThat(attempted(adyen)).containsExactly(REDSYS, ADYEN);

        down(ADYEN);
        assertThat(pay(10_051).payment().failureReason()).contains("DECLINED: insufficient_funds");
        down(STRIPE);
        assertThat(pay(51).payment().failureReason()).contains("DECLINED: mock_decline_51");
        chaos.resetAll();

        assertThat(pay(151, Currency.JPY, "JP").payment().failureReason()).contains("DECLINED: Not enough balance");
        assertThat(pay(10_050).payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(pay(10_052).payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    // ---------------------------------------------------------------------------------
    // Captura, reembolso y anulación contra el libro de la PSP
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("capture y refund de punta a punta: el pago y el libro de redsys cuadran en cada paso")
    void captureAndRefundAgainstLedger() {
        PaymentView authorized = pay(10_000).payment();
        String ref = authorized.gatewayReference().orElseThrow();
        SimulatedLedger ledger = simulator.redsys().ledger();
        assertThat(ledger.find(ref).orElseThrow().state()).isEqualTo(SimulatedLedger.State.AUTHORIZED);

        PaymentView captured = capture.capture(authorized.id());
        assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(ledger.find(ref).orElseThrow().state()).isEqualTo(SimulatedLedger.State.CAPTURED);

        PaymentView partial = refund.refund(authorized.id(), Money.of(3_000, Currency.EUR));
        assertThat(partial.refunded()).isEqualTo(Money.of(3_000, Currency.EUR));
        assertThat(ledger.find(ref).orElseThrow().refundedMinor()).isEqualTo(3_000);

        PaymentView full = refund.refund(authorized.id(), Money.of(7_000, Currency.EUR));
        assertThat(full.refunded()).isEqualTo(Money.of(10_000, Currency.EUR));
        assertThat(full.refunds()).extracting(r -> r.gatewayRefundReference())
                .allSatisfy(r -> assertThat(r).matches(ref + "-[0-9]{6}")).doesNotHaveDuplicates();
        assertThat(ledger.find(ref).orElseThrow().refundedMinor()).isEqualTo(10_000);

        // Reembolsar de más o capturar otra vez: lo para el dominio antes de llamar a la PSP.
        SimulatedLedger.Entry before = ledger.find(ref).orElseThrow();
        assertThatThrownBy(() -> refund.refund(authorized.id(), Money.of(1, Currency.EUR)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> capture.capture(authorized.id())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> cancel.cancel(authorized.id())).isInstanceOf(DomainException.class);
        assertThat(ledger.find(ref).orElseThrow()).isEqualTo(before);
        assertThat(payments.findById(authorized.id()).orElseThrow().refundedAmount())
                .isEqualTo(Money.of(10_000, Currency.EUR));
    }

    @Test
    @DisplayName("capturar con la PSP caída: GATEWAY_OPERATION_FAILED, el pago sigue AUTHORIZED y el libro igual; luego se captura")
    void captureWhileGatewayDown() {
        PaymentView authorized = pay(10_000).payment();
        String ref = authorized.gatewayReference().orElseThrow();
        down(REDSYS);

        assertThatThrownBy(() -> capture.capture(authorized.id()))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.GATEWAY_OPERATION_FAILED));
        assertThat(payments.findById(authorized.id()).orElseThrow().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(simulator.redsys().ledger().find(ref).orElseThrow().state())
                .isEqualTo(SimulatedLedger.State.AUTHORIZED);

        chaos.resetAll();
        assertThat(capture.capture(authorized.id()).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("anular en adyen de punta a punta: CANCELED en el pago y en el libro; ya no se puede capturar")
    void cancelOnAdyen() {
        down(REDSYS);
        PaymentView authorized = pay(10_000).payment();
        chaos.resetAll();
        assertThat(authorized.authorizedBy()).contains(ADYEN);
        String ref = authorized.gatewayReference().orElseThrow();

        assertThat(cancel.cancel(authorized.id()).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(simulator.adyen().ledger().find(ref).orElseThrow().state())
                .isEqualTo(SimulatedLedger.State.CANCELED);
        assertThatThrownBy(() -> capture.capture(authorized.id())).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("dos reembolsos simultáneos que juntos superan lo capturado: la PSP rechaza uno y el pago y el libro cuadran")
    void concurrentExcessRefundsStayConsistent() throws Exception {
        PaymentView authorized = pay(10_000).payment();
        capture.capture(authorized.id());
        String ref = authorized.gatewayReference().orElseThrow();
        // 200 ms de latencia real en redsys: los dos cargan el pago antes de que ninguno lo guarde.
        chaos.set(REDSYS, FaultConfig.none().withLatency(Duration.ofMillis(200)));

        List<Object> results = runTwice(() -> refund.refund(authorized.id(), Money.of(6_000, Currency.EUR)));

        assertThat(results).filteredOn(PaymentView.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(RuntimeException.class::isInstance).singleElement()
                .isInstanceOfAny(ApplicationException.class);
        long ledgerRefunded = simulator.redsys().ledger().find(ref).orElseThrow().refundedMinor();
        Payment stored = payments.findById(authorized.id()).orElseThrow();
        assertThat(stored.refundedAmount().amountMinor()).isEqualTo(6_000);
        assertThat(ledgerRefunded).isEqualTo(6_000);
    }

    @Test
    @DisplayName("dos reembolsos simultáneos compatibles (30 + 30 de 100): los dos OK y lo reembolsado en la PSP y en el pago cuadra")
    void concurrentCompatibleRefundsMatchLedger() throws Exception {
        PaymentView authorized = pay(10_000).payment();
        capture.capture(authorized.id());
        String ref = authorized.gatewayReference().orElseThrow();
        chaos.set(REDSYS, FaultConfig.none().withLatency(Duration.ofMillis(200)));

        List<Object> results = runTwice(() -> refund.refund(authorized.id(), Money.of(3_000, Currency.EUR)));

        assertThat(results).allMatch(PaymentView.class::isInstance);
        long ledgerRefunded = simulator.redsys().ledger().find(ref).orElseThrow().refundedMinor();
        long paymentRefunded = payments.findById(authorized.id()).orElseThrow().refundedAmount().amountMinor();
        assertThat(ledgerRefunded).isEqualTo(6_000);
        assertThat(paymentRefunded).as("reembolsado en el pago frente al libro de la PSP").isEqualTo(ledgerRefunded);
        assertPaymentMatchesLedger(authorized.id(), simulator.redsys().ledger(), "30 + 30");
    }

    /** Lanza la misma operación en dos hilos a la vez y devuelve resultado o excepción de cada uno. */
    private static List<Object> runTwice(Supplier<Object> op) throws Exception {
        return runConcurrently(List.of(op, op));
    }

    /** Lanza cada operación en su propio hilo, todas a la vez; devuelve resultado o excepción, en orden. */
    private static List<Object> runConcurrently(List<Supplier<Object>> ops) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(ops.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Supplier<Object> op : ops) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return op.get();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Supplier<Object>> times(int n, Supplier<Object> op) {
        return Collections.nCopies(n, op);
    }

    private static void assertGatewayDeclined(Object result) {
        assertThat(result).isInstanceOfSatisfying(ApplicationException.class, e -> {
            assertThat(e.code()).isEqualTo(ApplicationException.GATEWAY_OPERATION_FAILED);
            assertThat(e.getMessage()).contains("DECLINED");
        });
    }

    private <E extends PaymentEvent> long eventsOf(PaymentId id, Class<E> type) {
        return events.recent().stream().filter(type::isInstance).map(type::cast)
                .filter(e -> e.paymentId().equals(id)).count();
    }

    /** El estado del pago y el de su autorización en el libro de la PSP dicen lo mismo, y los eventos también. */
    private void assertPaymentMatchesLedger(PaymentId id, SimulatedLedger ledger, String context) {
        Payment stored = payments.findById(id).orElseThrow();
        SimulatedLedger.Entry entry = ledger.find(stored.gatewayReference().orElseThrow()).orElseThrow();
        SimulatedLedger.State expected = switch (stored.status()) {
            case AUTHORIZED -> SimulatedLedger.State.AUTHORIZED;
            case CAPTURED, PARTIALLY_REFUNDED, REFUNDED -> SimulatedLedger.State.CAPTURED;
            case CANCELED -> SimulatedLedger.State.CANCELED;
            default -> throw new AssertionError(context + ": estado inesperado " + stored.status());
        };
        assertThat(entry.state()).as(context + ": estado en la PSP frente al pago " + stored.status())
                .isEqualTo(expected);
        assertThat(stored.refundedAmount().amountMinor()).as(context + ": reembolsado pago vs PSP")
                .isEqualTo(entry.refundedMinor());
        assertThat(stored.refunds()).as(context + ": nº de reembolsos pago vs PSP").hasSize(entry.refundCount());
        assertThat(eventsOf(id, PaymentRefunded.class)).as(context + ": eventos de reembolso")
                .isEqualTo(entry.refundCount());
        assertThat(eventsOf(id, PaymentCaptured.class)).as(context + ": eventos de captura")
                .isEqualTo(stored.status().isCaptured() ? 1 : 0);
        assertThat(eventsOf(id, PaymentCanceled.class)).as(context + ": eventos de anulación")
                .isEqualTo(stored.status() == PaymentStatus.CANCELED ? 1 : 0);
    }

    @Test
    @DisplayName("libro real: 6 capturas a la vez → la PSP aprueba 1 y las otras 5 son DECLINED → GATEWAY_OPERATION_FAILED")
    void concurrentCapturesAgainstRealLedger() throws Exception {
        PaymentView authorized = pay(10_000).payment();
        chaos.set(REDSYS, FaultConfig.none().withLatency(Duration.ofMillis(200)));

        List<Object> results = runConcurrently(times(6, () -> capture.capture(authorized.id())));

        assertThat(results).filteredOn(PaymentView.class::isInstance).hasSize(1)
                .allSatisfy(v -> assertThat(((PaymentView) v).status()).isEqualTo(PaymentStatus.CAPTURED));
        List<Object> failures = results.stream().filter(r -> !(r instanceof PaymentView)).toList();
        assertThat(failures).hasSize(5).allSatisfy(MemoryProfileEndToEndTest::assertGatewayDeclined);
        assertThat(failures).allSatisfy(f -> assertThat(((Exception) f).getMessage()).contains("SIS0063"));
        assertPaymentMatchesLedger(authorized.id(), simulator.redsys().ledger(), "capturas");
    }

    @Test
    @DisplayName("libro real: 8 reembolsos compatibles a la vez (8 x 10 de 100) → los 8 OK; PSP = pago = 80")
    void manyCompatibleRefundsAgainstRealLedger() throws Exception {
        PaymentView authorized = pay(10_000).payment();
        capture.capture(authorized.id());
        chaos.set(REDSYS, FaultConfig.none().withLatency(Duration.ofMillis(200)));

        List<Object> results = runConcurrently(times(8, () -> refund.refund(authorized.id(),
                Money.of(1_000, Currency.EUR))));

        assertThat(results).allMatch(PaymentView.class::isInstance);
        assertThat(payments.findById(authorized.id()).orElseThrow().refundedAmount())
                .isEqualTo(Money.of(8_000, Currency.EUR));
        assertPaymentMatchesLedger(authorized.id(), simulator.redsys().ledger(), "reembolsos compatibles");
    }

    @Test
    @DisplayName("libro real: 8 reembolsos de 30 a la vez sobre 100 → caben 3; los otros 5 son DECLINED; PSP = pago = 90")
    void someRefundsFitAgainstRealLedger() throws Exception {
        PaymentView authorized = pay(10_000).payment();
        capture.capture(authorized.id());
        chaos.set(REDSYS, FaultConfig.none().withLatency(Duration.ofMillis(200)));

        List<Object> results = runConcurrently(times(8, () -> refund.refund(authorized.id(),
                Money.of(3_000, Currency.EUR))));

        assertThat(results).filteredOn(PaymentView.class::isInstance).hasSize(3);
        assertThat(results).filteredOn(r -> !(r instanceof PaymentView)).hasSize(5)
                .allSatisfy(MemoryProfileEndToEndTest::assertGatewayDeclined);
        assertThat(payments.findById(authorized.id()).orElseThrow().refundedAmount())
                .isEqualTo(Money.of(9_000, Currency.EUR));
        assertPaymentMatchesLedger(authorized.id(), simulator.redsys().ledger(), "reembolsos que no caben");
    }

    @Test
    @DisplayName("libro real: captura + reembolso a la vez sobre un AUTHORIZED → el reembolso no se adelanta a la captura")
    void captureAndRefundAgainstRealLedger() throws Exception {
        for (int round = 0; round < 5; round++) {
            PaymentView authorized = pay(10_000).payment();
            chaos.set(REDSYS, FaultConfig.none().withLatency(Duration.ofMillis(150)));
            List<Object> results = runConcurrently(List.of(
                    () -> capture.capture(authorized.id()),
                    () -> refund.refund(authorized.id(), Money.of(2_000, Currency.EUR))));
            chaos.resetAll();

            assertThat(results.get(0)).isInstanceOf(PaymentView.class);
            // El reembolso cargó el pago aún AUTHORIZED: lo para el ensayo del dominio, sin llamar a la PSP.
            assertThat(results.get(1)).isInstanceOf(DomainException.class);
            assertPaymentMatchesLedger(authorized.id(), simulator.redsys().ledger(), "ronda " + round);
        }
    }

    @Test
    @DisplayName("propiedad con el simulador real: 15 rondas x 8 hilos de operaciones mezcladas en las 4 PSP; libro = pago")
    void mixedOperationsPropertyAgainstRealLedger() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        List<GatewayId> byFee = List.of(REDSYS, ADYEN, STRIPE, MOCK);
        for (int round = 0; round < 15; round++) {
            GatewayId gateway = byFee.get(random.nextInt(4));
            // Para enrutar a la pasarela elegida se tiran las más baratas (y se avanza el reloj para
            // que sus breakers, si se abren, vuelvan a probar en la ronda siguiente).
            byFee.subList(0, byFee.indexOf(gateway)).forEach(this::down);
            PaymentView authorized = pay(10_000).payment();
            chaos.resetAll();
            testClock().advance(Duration.ofSeconds(20));
            String context = "semilla " + seed + ", ronda " + round + " en " + gateway;
            assertThat(authorized.authorizedBy()).as(context).contains(gateway);
            if (random.nextBoolean()) {
                capture.capture(authorized.id());
            }
            chaos.set(gateway, FaultConfig.none().withLatency(Duration.ofMillis(random.nextInt(40))));

            List<Supplier<Object>> ops = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                long amount = 1 + random.nextInt(5_000);
                switch (random.nextInt(3)) {
                    case 0 -> {
                        names.add("capture");
                        ops.add(() -> capture.capture(authorized.id()));
                    }
                    case 1 -> {
                        names.add("cancel");
                        ops.add(() -> cancel.cancel(authorized.id()));
                    }
                    default -> {
                        names.add("refund " + amount);
                        ops.add(() -> refund.refund(authorized.id(), Money.of(amount, Currency.EUR)));
                    }
                }
            }
            List<Object> results = runConcurrently(ops);
            chaos.resetAll();
            String full = context + ", ops " + names + ", resultados " + results;

            for (Object r : results) {
                if (r instanceof RuntimeException e) {
                    assertThat(e).as(full).isNotInstanceOf(ConcurrentPaymentModificationException.class);
                    if (e instanceof ApplicationException app) {
                        assertThat(app.code()).as(full).isEqualTo(ApplicationException.GATEWAY_OPERATION_FAILED);
                    } else {
                        assertThat(e).as(full).isInstanceOf(DomainException.class);
                    }
                }
            }
            assertPaymentMatchesLedger(authorized.id(), ledgerOf(gateway), full);
        }
    }

    private SimulatedLedger ledgerOf(GatewayId id) {
        return switch (id.value()) {
            case "stripe" -> simulator.stripe().ledger();
            case "adyen" -> simulator.adyen().ledger();
            case "redsys" -> simulator.redsys().ledger();
            default -> simulator.mock().ledger();
        };
    }

    // ---------------------------------------------------------------------------------
    // Reaper
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("payaf.reaper.enabled=false: no hay ReaperScheduler, pero el caso de uso sigue cableado")
    void reaperDisabledHasNoScheduler() {
        assertThat(context.getBeanNamesForType(ReaperScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(ReapStalePaymentsUseCase.class)).hasSize(1);
    }

    @Test
    @DisplayName("reaper de punta a punta: un PENDING huérfano de más de 2 min pasa a ABANDONED y el reintento es un replay")
    void reaperEndToEnd() {
        PaymentRequest req = request("orphan", 10_000, Currency.EUR, "ES");
        PaymentId id = PaymentId.of(UUID.randomUUID());
        // Como lo deja una petición que murió tras la transacción 1.
        transactions.inTransaction(() -> {
            idempotency.tryReserve(req.key(), req.fingerprint(), id, clock.instant());
            payments.save(Payment.create(id, req, clock.instant()));
            return null;
        });
        assertThatThrownBy(() -> authorize.authorize(new AuthorizePaymentCommand(req)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.IDEMPOTENCY_IN_PROGRESS));

        testClock().advance(Duration.ofMinutes(1));
        assertThat(reaper.reap(Duration.ofMinutes(2)).total()).isZero();

        testClock().advance(Duration.ofMinutes(1).plusMillis(1));
        assertThat(reaper.reap(Duration.ofMinutes(2))).isEqualTo(new ReapReport(1, 0));

        AuthorizationResult replay = authorize.authorize(new AuthorizePaymentCommand(req));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().id()).isEqualTo(id);
        assertThat(replay.payment().failureReason()).contains("ABANDONED");
    }

    @Test
    @DisplayName("reaper contra una autorización real en curso (redsys con latencia real): nunca queda incoherente")
    void reaperVersusInFlightAuthorization() throws Exception {
        chaos.set(REDSYS, FaultConfig.none().withLatency(Duration.ofMillis(300)));
        PaymentRequest req = request("in-flight", 10_000, Currency.EUR, "ES");
        CompletableFuture<Object> auth = CompletableFuture.supplyAsync(() -> {
            try {
                return authorize.authorize(new AuthorizePaymentCommand(req));
            } catch (RuntimeException e) {
                return e;
            }
        });
        // Mientras redsys «tarda», el reloj salta 3 min y pasa el reaper.
        Thread.sleep(100);
        testClock().advance(Duration.ofMinutes(3));
        ReapReport report = reaper.reap(Duration.ofMinutes(2));
        Object result = auth.get(10, TimeUnit.SECONDS);

        Payment stored = payments.findRecent(1).getFirst();
        AuthorizationResult replay = authorize.authorize(new AuthorizePaymentCommand(req));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().status()).isEqualTo(stored.status());
        if (result instanceof AuthorizationResult ok) {
            assertThat(report.total()).isZero();
            assertThat(stored.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(ok.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        } else {
            assertThat(result).isInstanceOf(ConcurrentPaymentModificationException.class);
            assertThat(report).isEqualTo(new ReapReport(1, 0));
            assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(stored.failureReason()).contains("ABANDONED");
            // Hueco aceptado (el reaper no reconcilia con la PSP): redsys sí retiene la autorización.
            assertThat(simulator.redsys().ledger().size()).isEqualTo(1);
        }
    }
}
