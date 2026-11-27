package dev.pedrogomez.payaf.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.application.fake.FakeTransactionRunner;
import dev.pedrogomez.payaf.application.fake.InMemoryCircuitBreakerStore;
import dev.pedrogomez.payaf.application.fake.InMemoryGatewayCatalog;
import dev.pedrogomez.payaf.application.fake.InMemoryIdempotencyStore;
import dev.pedrogomez.payaf.application.fake.InMemoryPaymentRepository;
import dev.pedrogomez.payaf.application.fake.InMemoryProcessedNotificationStore;
import dev.pedrogomez.payaf.application.fake.MutableClock;
import dev.pedrogomez.payaf.application.fake.RecordingEventPublisher;
import dev.pedrogomez.payaf.application.fake.ScriptedGateway;
import dev.pedrogomez.payaf.application.fake.SequentialPaymentIdGenerator;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Monta la aplicación entera con fakes en memoria: dos pasarelas (Stripe, más barata, y
 * Adyen), un breaker que se abre con {@value #FAILURE_THRESHOLD} fallos seguidos y un reloj
 * mutable. Es el «cableado» que en producción hará bootstrap.
 */
final class Harness {

    static final GatewayId STRIPE = GatewayId.of("stripe");
    static final GatewayId ADYEN = GatewayId.of("adyen");
    static final int FAILURE_THRESHOLD = 2;
    static final Duration OPEN_DURATION = Duration.ofSeconds(30);
    static final Duration LATENCY = Duration.ofMillis(100);

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    final FakeTransactionRunner transactions = new FakeTransactionRunner();
    final InMemoryPaymentRepository payments = new InMemoryPaymentRepository(transactions);
    final InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore(transactions);
    final InMemoryCircuitBreakerStore breakers;
    final RecordingEventPublisher events = new RecordingEventPublisher(transactions);
    final InMemoryProcessedNotificationStore processed = new InMemoryProcessedNotificationStore(transactions);
    final ScriptedGateway stripe = new ScriptedGateway(STRIPE, transactions, clock);
    final ScriptedGateway adyen = new ScriptedGateway(ADYEN, transactions, clock);
    final InMemoryGatewayCatalog catalog = new InMemoryGatewayCatalog();

    final AuthorizePaymentService authorizeService;
    final PaymentOperationsService operations;
    final HandleGatewayNotificationService notifications;
    final QueryGatewayHealthService health;

    /** Stripe (140 pb) y Adyen (200 pb), con un breaker que se abre con {@value #FAILURE_THRESHOLD} fallos. */
    Harness() {
        this(CircuitBreakerConfig.of(FAILURE_THRESHOLD, OPEN_DURATION), true);
    }

    /**
     * Con otra configuración de breakers y, si {@code defaultGateways} es falso, con el
     * catálogo vacío para que el test registre sus propias pasarelas.
     */
    Harness(CircuitBreakerConfig breakerConfig, boolean defaultGateways) {
        this(breakerConfig, defaultGateways, null);
    }

    /**
     * Como el anterior, pero los servicios leen la hora de {@code serviceClock} (por ejemplo,
     * un reloj que falla en una llamada concreta) en lugar de {@link #clock}.
     */
    Harness(CircuitBreakerConfig breakerConfig, boolean defaultGateways, java.time.Clock serviceClock) {
        java.time.Clock clock = serviceClock == null ? this.clock : serviceClock;
        breakers = new InMemoryCircuitBreakerStore(breakerConfig, transactions);
        if (defaultGateways) {
            catalog.register(profile(STRIPE, 140), stripe).register(profile(ADYEN, 200), adyen);
        }
        authorizeService = new AuthorizePaymentService(payments, idempotency, catalog, breakers, events,
                transactions, new SequentialPaymentIdGenerator(), clock);
        operations = new PaymentOperationsService(payments, catalog, breakers, events, transactions, clock);
        notifications = new HandleGatewayNotificationService(payments, processed, events, transactions, clock);
        health = new QueryGatewayHealthService(catalog, breakers);
        queries = new QueryPaymentsService(payments, transactions);
    }

    static GatewayProfile profile(GatewayId id, int feeBasisPoints) {
        return new GatewayProfile(id, Set.of(Currency.EUR), Set.of(), feeBasisPoints, Map.of(), true);
    }

    static PaymentRequest request(String key, long amountMinor) {
        return new PaymentRequest(Money.of(amountMinor, Currency.EUR), CountryCode.of("ES"), "pedido-" + key,
                IdempotencyKey.of(key));
    }

    final QueryPaymentsService queries;

    PaymentView queryView(PaymentId id) {
        return queries.get(id);
    }

    AuthorizationResult authorize(String key) {
        return authorizeService.authorize(new AuthorizePaymentCommand(request(key, 10_000)));
    }

    AuthorizationResult authorize(PaymentRequest request) {
        return authorizeService.authorize(new AuthorizePaymentCommand(request));
    }

    /** Nada se ha llamado fuera de su sitio: pasarelas sin transacción, persistencia dentro. */
    void assertTransactionDiscipline() {
        assertThat(transactions.violations()).isEmpty();
    }
}
