package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.AUTHORIZE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.CAPTURE;
import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.REFUND;
import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.OPEN_DURATION;
import static dev.pedrogomez.payaf.application.service.Harness.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.GatewayHealthView;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerSnapshot;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.InvalidValueException;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Consultas (salud y pagos) y la vista de solo lectura {@link PaymentView}. */
class QueriesAndViewsTest {

    private static final GatewayId CLOSED = GatewayId.of("g-closed");
    private static final GatewayId FAILING = GatewayId.of("g-failing");
    private static final GatewayId OPEN = GatewayId.of("g-open");
    private static final GatewayId DUE = GatewayId.of("g-due");
    private static final GatewayId TRIAL = GatewayId.of("g-trial");

    private Harness h = new Harness();

    @AfterEach
    void transactionDiscipline() {
        h.assertTransactionDiscipline();
    }

    // ---------------------------------------------------------------------------------
    // health(now)
    // ---------------------------------------------------------------------------------

    /** Cinco pasarelas con los breakers en estados distintos; devuelve el instante de la consulta. */
    private Instant fiveBreakers() {
        h = new Harness(CircuitBreakerConfig.of(3, OPEN_DURATION), false);
        for (GatewayId id : List.of(CLOSED, FAILING, OPEN, DUE, TRIAL)) {
            h.catalog.register(Harness.profile(id, 100), new FunctionalGateway(id, h.transactions,
                    a -> GatewayResponse.approved("r", LATENCY)));
        }
        Instant now = h.clock.instant();
        h.breakers.peek(FAILING).recordFailure(now.minusSeconds(5));
        h.breakers.peek(FAILING).recordFailure(now.minusSeconds(4));
        for (int i = 0; i < 3; i++) {
            h.breakers.peek(OPEN).recordFailure(now.minusSeconds(10));
            h.breakers.peek(DUE).recordFailure(now.minus(OPEN_DURATION).minusSeconds(1));
            h.breakers.peek(TRIAL).recordFailure(now.minus(OPEN_DURATION).minusSeconds(20));
        }
        assertThat(h.breakers.peek(TRIAL).tryAcquirePermission(now.minusSeconds(5))).isTrue();
        // Se descartan los eventos de la preparación.
        for (GatewayId id : List.of(CLOSED, FAILING, OPEN, DUE, TRIAL)) {
            h.breakers.peek(id).pullEvents();
        }
        return now;
    }

    private Map<GatewayId, CircuitBreakerSnapshot> snapshots(List<GatewayId> ids) {
        Map<GatewayId, CircuitBreakerSnapshot> map = new LinkedHashMap<>();
        for (GatewayId id : ids) {
            map.put(id, h.breakers.peek(id).snapshot());
        }
        return map;
    }

    @Test
    @DisplayName("health(now) no modifica ningún breaker: snapshots, estado y eventos idénticos antes y después")
    void healthIsReadOnly() {
        Instant now = fiveBreakers();
        List<GatewayId> ids = List.of(CLOSED, FAILING, OPEN, DUE, TRIAL);
        Map<GatewayId, CircuitBreakerSnapshot> before = snapshots(ids);
        int saves = h.breakers.saves();

        List<GatewayHealthView> views = h.health.health(now);
        h.health.health(now.plus(OPEN_DURATION.multipliedBy(10)));

        assertThat(snapshots(ids)).isEqualTo(before);
        assertThat(h.breakers.saves()).isEqualTo(saves);
        for (GatewayId id : ids) {
            assertThat(h.breakers.peek(id).pullEvents()).as("eventos pendientes de %s", id).isEmpty();
        }
        assertThat(h.events.published()).isEmpty();
        assertThat(views).extracting(v -> v.profile().id()).containsExactly(CLOSED, FAILING, OPEN, DUE, TRIAL);
    }

    @Test
    @DisplayName("health(now) muestra el estado efectivo: OPEN vencido como HALF_OPEN, y el breaker real sigue OPEN")
    void healthShowsEffectiveStates() {
        Instant now = fiveBreakers();

        Map<GatewayId, CircuitBreakerSnapshot> shown = new LinkedHashMap<>();
        h.health.health(now).forEach(v -> shown.put(v.profile().id(), v.circuit()));

        assertThat(shown.get(CLOSED).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(shown.get(FAILING).state()).isEqualTo(CircuitState.CLOSED);
        assertThat(shown.get(FAILING).consecutiveFailures()).isEqualTo(2);
        assertThat(shown.get(OPEN).state()).isEqualTo(CircuitState.OPEN);
        assertThat(shown.get(OPEN).retryAt()).contains(now.minusSeconds(10).plus(OPEN_DURATION));
        assertThat(shown.get(DUE).state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(shown.get(DUE).retryAt()).isEmpty();
        assertThat(shown.get(DUE).trialsInFlight()).isZero();
        assertThat(shown.get(TRIAL).state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(shown.get(TRIAL).trialsInFlight()).isEqualTo(1);

        assertThat(h.breakers.peek(DUE).state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("el paso a HALF_OPEN en el panel es exacto en retryAt (un nanosegundo antes sigue OPEN)")
    void healthBoundary() {
        Instant now = fiveBreakers();
        Instant retryAt = h.breakers.peek(OPEN).snapshot().retryAt().orElseThrow();

        assertThat(stateOf(OPEN, retryAt.minusNanos(1))).isEqualTo(CircuitState.OPEN);
        assertThat(stateOf(OPEN, retryAt)).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(h.breakers.peek(OPEN).state()).isEqualTo(CircuitState.OPEN);
        assertThat(now).isBefore(retryAt);
    }

    private CircuitState stateOf(GatewayId id, Instant at) {
        return h.health.health(at).stream().filter(v -> v.profile().id().equals(id)).findFirst().orElseThrow()
                .circuit().state();
    }

    @Test
    @DisplayName("después de consultar la salud, la siguiente autorización sí hace avanzar el breaker y publica CircuitHalfOpened")
    void healthDoesNotStealTheTransition() {
        for (int i = 0; i < Harness.FAILURE_THRESHOLD; i++) {
            h.stripe.on(AUTHORIZE, GatewayResponse.error("x", LATENCY));
            h.adyen.on(AUTHORIZE, GatewayResponse.approved("p" + i, LATENCY));
            h.authorize("k" + i);
        }
        h.clock.advance(OPEN_DURATION);
        CircuitBreakerSnapshot before = h.breakers.peek(STRIPE).snapshot();

        h.health.health(h.clock.instant());
        assertThat(h.breakers.peek(STRIPE).snapshot()).isEqualTo(before);

        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_back", LATENCY));
        h.authorize("k-after");
        assertThat(h.events.published(CircuitHalfOpened.class)).hasSize(1);
    }

    @Test
    @DisplayName("health(null) se rechaza")
    void healthNull() {
        assertThatThrownBy(() -> h.health.health(null)).isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------------------
    // get / recent
    // ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "recent({0})")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("recent con límite < 1: InvalidValueException sin tocar el repositorio")
    void recentInvalidLimit(int limit) {
        assertThatThrownBy(() -> h.queries.recent(limit)).isInstanceOf(InvalidValueException.class);
        assertThat(h.transactions.journal()).isEmpty();
    }

    @Test
    @DisplayName("recent devuelve del más reciente al más antiguo y respeta el límite")
    void recentOrderAndLimit() {
        List<PaymentId> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_" + i, LATENCY));
            ids.add(h.authorize("k" + i).payment().id());
        }

        assertThat(h.queries.recent(1)).extracting(PaymentView::id).containsExactly(ids.get(2));
        assertThat(h.queries.recent(Integer.MAX_VALUE)).extracting(PaymentView::id)
                .containsExactly(ids.get(2), ids.get(1), ids.get(0));
    }

    @Test
    @DisplayName("get de un pago que no existe: PAYMENT_NOT_FOUND; get(null): NullPointerException")
    void getNotFound() {
        assertThatThrownBy(() -> h.queries.get(PaymentId.of(new UUID(1, 2))))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.code()).isEqualTo(ApplicationException.PAYMENT_NOT_FOUND));
        assertThatThrownBy(() -> h.queries.get(null)).isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------------------------
    // PaymentView
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("PaymentView es inmutable: sus listas no se pueden modificar y no cambia aunque el pago cambie después")
    void paymentViewIsImmutable() {
        h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));
        AuthorizationResult result = h.authorize("k1");
        PaymentId id = result.payment().id();
        h.stripe.on(CAPTURE, GatewayResponse.approved("ch_1", LATENCY));
        h.stripe.on(REFUND, GatewayResponse.approved("re_1", LATENCY));
        h.operations.capture(id);
        PaymentView view = h.operations.refund(id, Money.of(100, Currency.EUR));

        assertThatThrownBy(() -> view.refunds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.decision().candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        // La vista es una foto: el agregado sigue vivo y cambia, la vista no.
        Payment live = h.payments.stored(id);
        PaymentView snapshot = PaymentView.from(live);
        live.refund(Money.of(200, Currency.EUR), "re_2", h.clock.instant());
        assertThat(snapshot.refunds()).hasSize(1);
        assertThat(snapshot.refunded()).isEqualTo(Money.of(100, Currency.EUR));

        // Copiar de una lista mutable tampoco deja un enlace a ella.
        List<dev.pedrogomez.payaf.domain.payment.Refund> mutable = new ArrayList<>(view.refunds());
        PaymentView copy = new PaymentView(view.id(), view.amount(), view.country(), view.merchantReference(),
                view.status(), view.authorizedBy(), view.gatewayReference(), view.failureReason(), view.refunded(),
                view.remainingRefundable(), mutable, new ArrayList<>(view.attempts()), view.createdAt(),
                view.updatedAt());
        mutable.clear();
        assertThat(copy.refunds()).hasSize(1);
    }

    @Test
    @DisplayName("ningún tipo de port.in expone el agregado Payment (ni en componentes de record ni en firmas de métodos)")
    void portsInDoNotExposeTheAggregate() throws Exception {
        List<String> leaks = new ArrayList<>();
        for (Class<?> type : portInClasses()) {
            if (type.isRecord()) {
                for (RecordComponent c : type.getRecordComponents()) {
                    if (mentionsPayment(c.getGenericType())) {
                        leaks.add(type.getSimpleName() + "." + c.getName());
                    }
                }
            }
            for (Method m : type.getDeclaredMethods()) {
                if (m.isSynthetic() || (m.getName().equals("from") && type == PaymentView.class)) {
                    continue; // from(Payment) es la fábrica: recibe el agregado, no lo devuelve
                }
                if (mentionsPayment(m.getGenericReturnType())) {
                    leaks.add(type.getSimpleName() + "." + m.getName() + "()");
                }
                for (Type p : m.getGenericParameterTypes()) {
                    if (mentionsPayment(p)) {
                        leaks.add(type.getSimpleName() + "." + m.getName() + "(Payment)");
                    }
                }
            }
        }
        assertThat(leaks).isEmpty();
    }

    private static boolean mentionsPayment(Type type) {
        if (type == Payment.class) {
            return true;
        }
        if (type instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                if (mentionsPayment(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Class<?>> portInClasses() throws URISyntaxException, ClassNotFoundException {
        String pkg = PaymentView.class.getPackageName();
        File dir = new File(PaymentView.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File pkgDir = new File(dir, pkg.replace('.', File.separatorChar));
        List<Class<?>> classes = new ArrayList<>();
        for (String name : Optional.ofNullable(pkgDir.list()).orElse(new String[0])) {
            if (name.endsWith(".class") && !name.contains("$") && !name.equals("package-info.class")) {
                classes.add(Class.forName(pkg + "." + name.substring(0, name.length() - ".class".length())));
            }
        }
        assertThat(classes).as("clases de port.in encontradas").hasSizeGreaterThan(10);
        return classes;
    }
}
