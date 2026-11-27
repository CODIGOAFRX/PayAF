package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.application.service.AuthorizePaymentService;
import dev.pedrogomez.payaf.application.service.PaymentOperationsService;
import dev.pedrogomez.payaf.application.service.ReapStalePaymentsService;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.PaymentEvent;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Tester (fase 4a): los casos de uso REALES montados sobre los adaptadores REALES de memoria
 * (repositorio, idempotencia, breakers, eventos y transacciones). Solo la pasarela es de test,
 * porque el simulador de PSP es otro adaptador y este módulo no puede depender de él.
 */
final class RealAdaptersHarness {

    static final GatewayId PSP = GatewayId.of("psp");
    static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");

    /** Reloj que se mueve a mano; seguro para hilos. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /**
     * Pasarela de test: cada operación pasa por un gancho que el test puede cambiar (para
     * esperar en una barrera, dormir o contestar otra cosa). Por defecto aprueba todo.
     */
    static final class HookedGateway implements PaymentGateway {

        private final AtomicInteger sequence = new AtomicInteger();
        volatile Function<GatewayAuthorization, GatewayResponse> onAuthorize =
                auth -> GatewayResponse.approved("auth-" + sequence.incrementAndGet(), Duration.ofMillis(1));
        volatile Function<String, GatewayResponse> onOperation =
                op -> GatewayResponse.approved(op + "-" + sequence.incrementAndGet(), Duration.ofMillis(1));

        @Override
        public GatewayId id() {
            return PSP;
        }

        @Override
        public GatewayResponse authorize(GatewayAuthorization authorization) {
            return onAuthorize.apply(authorization);
        }

        @Override
        public GatewayResponse capture(String gatewayReference, Money amount) {
            return onOperation.apply("capture");
        }

        @Override
        public GatewayResponse refund(String gatewayReference, Money amount) {
            return onOperation.apply("refund");
        }

        @Override
        public GatewayResponse cancel(String gatewayReference) {
            return onOperation.apply("cancel");
        }
    }

    final MutableClock clock = new MutableClock(T0);
    final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    final InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
    final InMemoryCircuitBreakerStore breakers = new InMemoryCircuitBreakerStore(
            CircuitBreakerConfig.of(1_000, Duration.ofSeconds(15)));
    final InMemoryEventPublisher events = new InMemoryEventPublisher();
    final InMemoryTransactionRunner transactions = new InMemoryTransactionRunner();
    final HookedGateway gateway = new HookedGateway();
    final GatewayCatalog catalog = new GatewayCatalog() {
        private final GatewayProfile profile = new GatewayProfile(PSP, Set.of(Currency.EUR), Set.of(), 100,
                Map.of(), true);

        @Override
        public List<GatewayProfile> profiles() {
            return List.of(profile);
        }

        @Override
        public Optional<PaymentGateway> gateway(GatewayId id) {
            return PSP.equals(id) ? Optional.of(gateway) : Optional.empty();
        }
    };
    final AuthorizePaymentService authorize = new AuthorizePaymentService(payments, idempotency, catalog, breakers,
            events, transactions, () -> PaymentId.of(UUID.randomUUID()), clock);
    final PaymentOperationsService operations = new PaymentOperationsService(payments, catalog, breakers, events,
            transactions, clock);
    final ReapStalePaymentsService reaper = new ReapStalePaymentsService(payments, idempotency, events,
            transactions, clock);

    static PaymentRequest request(String key, long amountMinor) {
        return new PaymentRequest(Money.of(amountMinor, Currency.EUR), CountryCode.of("ES"), "pedido-" + key,
                IdempotencyKey.of(key));
    }

    AuthorizationResult authorize(String key, long amountMinor) {
        return authorize.authorize(new AuthorizePaymentCommand(request(key, amountMinor)));
    }

    /** Eventos publicados de un pago concreto y de un tipo. */
    <E extends PaymentEvent> List<E> eventsOf(PaymentId id, Class<E> type) {
        return events.recent().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(e -> e.paymentId().equals(id))
                .toList();
    }

    List<DomainEvent> allEvents() {
        return events.recent();
    }
}
