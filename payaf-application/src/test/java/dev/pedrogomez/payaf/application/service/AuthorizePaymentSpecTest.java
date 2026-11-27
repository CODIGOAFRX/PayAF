package dev.pedrogomez.payaf.application.service;

import static dev.pedrogomez.payaf.application.fake.ScriptedGateway.Operation.AUTHORIZE;
import static dev.pedrogomez.payaf.application.service.Harness.ADYEN;
import static dev.pedrogomez.payaf.application.service.Harness.LATENCY;
import static dev.pedrogomez.payaf.application.service.Harness.OPEN_DURATION;
import static dev.pedrogomez.payaf.application.service.Harness.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.event.GatewayAttemptRecorded;
import dev.pedrogomez.payaf.domain.event.PaymentAuthorized;
import dev.pedrogomez.payaf.domain.event.PaymentCreated;
import dev.pedrogomez.payaf.domain.event.PaymentFailed;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.routing.RejectionReason;
import dev.pedrogomez.payaf.domain.routing.RoutingCandidate;
import dev.pedrogomez.payaf.domain.routing.RoutingRejection;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Especificación de la fase 3 para {@link AuthorizePaymentService}: transacciones,
 * idempotencia, failover, breakers y eventos. Complementa a {@link AuthorizePaymentServiceTest}.
 */
class AuthorizePaymentSpecTest {

    private static final GatewayId CHEAP = GatewayId.of("cheap");
    private static final GatewayId MID = GatewayId.of("mid");
    private static final GatewayId PRICEY = GatewayId.of("pricey");
    private static final GatewayId LAST = GatewayId.of("last");

    private Harness h = new Harness();

    @AfterEach
    void transactionDisciplineOnEveryPath() {
        h.assertTransactionDiscipline();
    }

    private static GatewayProfile eur(GatewayId id, int bps) {
        return Harness.profile(id, bps);
    }

    private static PaymentRequest request(String key) {
        return Harness.request(key, 10_000);
    }

    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("transacción 1: reserva y creación")
    class FirstTransaction {

        @Test
        @DisplayName("mientras se habla con la pasarela, el pago ya está guardado en PENDING, su PaymentCreated publicado y la clave reservada sin completar")
        void paymentExistsPendingDuringGatewayCall() {
            h = new Harness(CircuitBreakerConfig.of(2, OPEN_DURATION), false);
            AtomicReference<Payment> seen = new AtomicReference<>();
            AtomicReference<Boolean> completedDuringCall = new AtomicReference<>();
            AtomicReference<Integer> createdEventsDuringCall = new AtomicReference<>();
            IdempotencyKey key = request("k1").key();
            h.catalog.register(eur(STRIPE, 140), new FunctionalGateway(STRIPE, h.transactions, auth -> {
                seen.set(h.payments.stored(auth.paymentId()));
                completedDuringCall.set(h.idempotency.isCompleted(key));
                createdEventsDuringCall.set(h.events.published(PaymentCreated.class).size());
                return GatewayResponse.approved("ch_1", LATENCY);
            }));

            AuthorizationResult result = h.authorize("k1");

            assertThat(seen.get()).isNotNull();
            assertThat(seen.get().status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(seen.get().id()).isEqualTo(result.payment().id());
            assertThat(h.idempotency.isReserved(key)).isTrue();
            assertThat(completedDuringCall.get()).isFalse();
            assertThat(createdEventsDuringCall.get()).isEqualTo(1);
            assertThat(h.idempotency.isCompleted(key)).isTrue();
        }

        @Test
        @DisplayName("el reparto de operaciones es: T1 = reservar, guardar, publicar; fuera = pasarelas; T2 = guardar, publicar, completar")
        void operationsAreSplitBetweenTwoTransactions() {
            h.stripe.on(AUTHORIZE, GatewayResponse.error("http_503", LATENCY));
            h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_1", LATENCY));

            h.authorize("k1");

            assertThat(h.transactions.transactionCount()).isEqualTo(2);
            assertThat(h.transactions.journalOf(1)).containsExactly(
                    "IdempotencyStore.tryReserve", "PaymentRepository.save", "EventPublisher.publish");
            assertThat(h.transactions.journalOf(2)).containsExactly(
                    "PaymentRepository.save", "EventPublisher.publish", "IdempotencyStore.complete");
            assertThat(h.transactions.journalOf(0))
                    .contains("stripe.AUTHORIZE", "adyen.AUTHORIZE")
                    .allSatisfy(op -> assertThat(op).doesNotStartWith("PaymentRepository")
                            .doesNotStartWith("EventPublisher").doesNotStartWith("IdempotencyStore"));
        }
    }

    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("idempotencia")
    class Idempotency {

        @Test
        @DisplayName("replay de un pago FAILED: sigue FAILED, decisión vacía y no se reintenta ninguna pasarela")
        void replayOfFailedPaymentDoesNotRetry() {
            h.stripe.on(AUTHORIZE, GatewayResponse.error("x", LATENCY));
            h.adyen.on(AUTHORIZE, GatewayResponse.timeout(LATENCY));
            AuthorizationResult first = h.authorize("k1");
            int transactionsBefore = h.transactions.transactionCount();
            int eventsBefore = h.events.published().size();
            int journalBefore = h.transactions.journal().size();

            AuthorizationResult again = h.authorize("k1");

            assertThat(again.replayed()).isTrue();
            assertThat(again.payment()).isEqualTo(first.payment());
            assertThat(again.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(again.decision().candidates()).isEmpty();
            assertThat(again.decision().rejections()).isEmpty();
            assertThat(again.attempts()).isEqualTo(first.attempts());
            assertThat(h.stripe.calls()).hasSize(1);
            assertThat(h.adyen.calls()).hasSize(1);
            assertThat(h.transactions.transactionCount()).isEqualTo(transactionsBefore + 1);
            assertThat(h.events.published()).hasSize(eventsBefore);
            // El replay no consulta ni hace avanzar los breakers.
            assertThat(h.transactions.journal().subList(journalBefore, h.transactions.journal().size()))
                    .extracting(e -> e.what())
                    .containsExactly("IdempotencyStore.tryReserve", "PaymentRepository.findById");
        }

        @Test
        @DisplayName("el replay no hace pasar a HALF_OPEN un breaker OPEN cuyo tiempo ya ha vencido")
        void replayDoesNotAdvanceBreakers() {
            for (int i = 0; i < Harness.FAILURE_THRESHOLD; i++) {
                h.stripe.on(AUTHORIZE, GatewayResponse.error("x", LATENCY));
                h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_" + i, LATENCY));
                h.authorize("k" + i);
            }
            h.clock.advance(OPEN_DURATION.plusSeconds(1));
            int halfOpenedBefore = h.events.published(CircuitHalfOpened.class).size();

            AuthorizationResult again = h.authorize("k0");

            assertThat(again.replayed()).isTrue();
            assertThat(h.breakers.peek(STRIPE).state()).isEqualTo(CircuitState.OPEN);
            assertThat(h.breakers.peek(STRIPE).pullEvents()).isEmpty();
            assertThat(h.events.published(CircuitHalfOpened.class)).hasSize(halfOpenedBefore);
        }

        @Test
        @DisplayName("otra huella con la clave EN CURSO da IDEMPOTENCY_CONFLICT (no IN_PROGRESS) y no llama a nadie")
        void conflictWhileInProgress() {
            PaymentRequest original = request("k1");
            h.transactions.inTransaction(() -> h.idempotency.tryReserve(original.key(), original.fingerprint(),
                    PaymentId.of(new UUID(9, 9)), h.clock.instant()));

            assertThatThrownBy(() -> h.authorize(Harness.request("k1", 55_555)))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            e -> assertThat(e.code()).isEqualTo(ApplicationException.IDEMPOTENCY_CONFLICT));
            assertThat(h.stripe.calls()).isEmpty();
            assertThat(h.adyen.calls()).isEmpty();
            assertThat(h.payments.size()).isZero();
        }

        @Test
        @DisplayName("otra huella con la clave completada da IDEMPOTENCY_CONFLICT sin crear pago ni publicar eventos")
        void conflictWhenCompleted() {
            h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_1", LATENCY));
            h.authorize("k1");
            int events = h.events.published().size();

            // Cambia solo la referencia del comercio: también cambia la huella.
            PaymentRequest other = new PaymentRequest(Money.of(10_000, Currency.EUR), CountryCode.of("ES"),
                    "otro-pedido", IdempotencyKey.of("k1"));

            assertThatThrownBy(() -> h.authorize(other))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            e -> assertThat(e.code()).isEqualTo(ApplicationException.IDEMPOTENCY_CONFLICT));
            assertThat(h.payments.size()).isEqualTo(1);
            assertThat(h.events.published()).hasSize(events);
            assertThat(h.stripe.calls(AUTHORIZE)).isEqualTo(1);
        }

        @Test
        @DisplayName("IN_PROGRESS con la misma huella no crea un segundo pago ni toca breakers")
        void inProgressSameFingerprint() {
            PaymentRequest original = request("k1");
            h.transactions.inTransaction(() -> h.idempotency.tryReserve(original.key(), original.fingerprint(),
                    PaymentId.of(new UUID(9, 9)), h.clock.instant()));

            assertThatThrownBy(() -> h.authorize("k1"))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            e -> assertThat(e.code()).isEqualTo(ApplicationException.IDEMPOTENCY_IN_PROGRESS));
            assertThat(h.payments.size()).isZero();
            assertThat(h.events.published()).isEmpty();
            assertThat(h.breakers.saves()).isZero();
        }

        @Test
        @DisplayName("un comando nulo se rechaza sin abrir transacciones")
        void nullCommand() {
            assertThatThrownBy(() -> h.authorizeService.authorize(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new AuthorizePaymentCommand(null)).isInstanceOf(NullPointerException.class);
            assertThat(h.transactions.transactionCount()).isZero();
        }
    }

    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("failover")
    class Failover {

        /** Cuatro pasarelas registradas en un orden distinto del de su comisión. */
        private final Map<GatewayId, FunctionalGateway> gateways = new java.util.LinkedHashMap<>();

        private void fourGateways(GatewayResponseOrThrow cheap, GatewayResponseOrThrow mid,
                                  GatewayResponseOrThrow pricey, GatewayResponseOrThrow last) {
            h = new Harness(CircuitBreakerConfig.of(5, OPEN_DURATION), false);
            register(LAST, 900, last);
            register(MID, 200, mid);
            register(CHEAP, 100, cheap);
            register(PRICEY, 300, pricey);
        }

        private void register(GatewayId id, int bps, GatewayResponseOrThrow behaviour) {
            FunctionalGateway gateway = new FunctionalGateway(id, h.transactions, a -> behaviour.get());
            gateways.put(id, gateway);
            h.catalog.register(eur(id, bps), gateway);
        }

        @Test
        @DisplayName("ERROR, TIMEOUT y excepción pasan a la siguiente en el orden de la decisión; APPROVED autoriza con esa pasarela")
        void walksCandidatesInDecisionOrder() {
            fourGateways(
                    () -> GatewayResponse.error("http_500", LATENCY),
                    () -> GatewayResponse.timeout(Duration.ofSeconds(5)),
                    () -> {
                        throw new IllegalStateException("socket cerrado");
                    },
                    () -> GatewayResponse.approved("last_ref", LATENCY));

            AuthorizationResult result = h.authorize("k1");

            assertThat(result.decision().candidates()).extracting(RoutingCandidate::gateway)
                    .containsExactly(CHEAP, MID, PRICEY, LAST);
            assertThat(result.attempts())
                    .extracting(GatewayAttempt::gateway, GatewayAttempt::outcome, GatewayAttempt::code)
                    .containsExactly(
                            tuple(CHEAP, AttemptOutcome.ERROR, Optional.of("http_500")),
                            tuple(MID, AttemptOutcome.TIMEOUT, Optional.empty()),
                            tuple(PRICEY, AttemptOutcome.ERROR, Optional.of(ServiceSupport.ADAPTER_EXCEPTION)),
                            tuple(LAST, AttemptOutcome.APPROVED, Optional.empty()));
            assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.payment().authorizedBy()).contains(LAST);
            assertThat(result.payment().gatewayReference()).contains("last_ref");
            assertThat(result.payment().attempts()).isEqualTo(result.attempts());
            assertThat(h.payments.stored(result.payment().id()).attempts()).isEqualTo(result.attempts());
            gateways.values().forEach(g -> assertThat(g.authorizeCalls()).isEqualTo(1));
            // Cada intento queda registrado en su breaker.
            assertThat(h.breakers.peek(CHEAP).snapshot().consecutiveFailures()).isEqualTo(1);
            assertThat(h.breakers.peek(MID).snapshot().consecutiveFailures()).isEqualTo(1);
            assertThat(h.breakers.peek(PRICEY).snapshot().consecutiveFailures()).isEqualTo(1);
            assertThat(h.breakers.peek(LAST).snapshot().consecutiveFailures()).isZero();
            // Los instantes de los intentos no retroceden.
            for (int i = 1; i < result.attempts().size(); i++) {
                assertThat(result.attempts().get(i).at()).isAfterOrEqualTo(result.attempts().get(i - 1).at());
            }
        }

        @Test
        @DisplayName("un DECLINED en la segunda corta el failover: FAILED con el código y las demás no se llaman")
        void declinedStopsFailover() {
            fourGateways(
                    () -> GatewayResponse.error("http_500", LATENCY),
                    () -> GatewayResponse.declined("do_not_honor", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY));

            AuthorizationResult result = h.authorize("k1");

            assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.payment().failureReason()).contains("DECLINED: do_not_honor");
            assertThat(result.payment().authorizedBy()).isEmpty();
            assertThat(result.attempts()).extracting(GatewayAttempt::gateway).containsExactly(CHEAP, MID);
            assertThat(gateways.get(PRICEY).authorizeCalls()).isZero();
            assertThat(gateways.get(LAST).authorizeCalls()).isZero();
        }

        @Test
        @DisplayName("un DECLINED sin código deja el motivo «DECLINED»")
        void declinedWithoutCode() {
            fourGateways(
                    () -> GatewayResponse.declined(null, LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY));

            assertThat(h.authorize("k1").payment().failureReason()).contains(AuthorizePaymentService.DECLINED_PREFIX);
        }

        @Test
        @DisplayName("si el adaptador devuelve null cuenta como ERROR ADAPTER_EXCEPTION y se pasa a la siguiente")
        void nullResponseIsAnError() {
            fourGateways(
                    () -> null,
                    () -> GatewayResponse.approved("mid_ref", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY));

            AuthorizationResult result = h.authorize("k1");

            assertThat(result.attempts()).extracting(GatewayAttempt::outcome, GatewayAttempt::code)
                    .containsExactly(tuple(AttemptOutcome.ERROR, Optional.of(ServiceSupport.ADAPTER_EXCEPTION)),
                            tuple(AttemptOutcome.APPROVED, Optional.empty()));
            assertThat(result.payment().authorizedBy()).contains(MID);
        }

        @Test
        @DisplayName("todas fallan técnicamente: FAILED con NO_GATEWAY_AVAILABLE, cuatro intentos y la clave completada")
        void allFail() {
            fourGateways(
                    () -> GatewayResponse.error("a", LATENCY),
                    () -> GatewayResponse.timeout(LATENCY),
                    () -> {
                        throw new RuntimeException("b");
                    },
                    () -> GatewayResponse.error("c", LATENCY));

            AuthorizationResult result = h.authorize("k1");

            assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.NO_GATEWAY_AVAILABLE);
            assertThat(result.attempts()).hasSize(4);
            assertThat(h.idempotency.isCompleted(request("k1").key())).isTrue();
            assertThat(h.events.published(PaymentFailed.class)).singleElement()
                    .satisfies(e -> assertThat(e.reason()).isEqualTo(AuthorizePaymentService.NO_GATEWAY_AVAILABLE));
        }

        @Test
        @DisplayName("sin candidatas (deshabilitada, otra divisa, otro país): FAILED con NO_GATEWAY_AVAILABLE y sin intentos")
        void noCandidates() {
            h = new Harness(CircuitBreakerConfig.of(2, OPEN_DURATION), false);
            FunctionalGateway never = new FunctionalGateway(CHEAP, h.transactions, a -> {
                throw new AssertionError("no debía llamarse");
            });
            h.catalog.register(new GatewayProfile(CHEAP, Set.of(Currency.EUR), Set.of(), 100, Map.of(), false), never);
            h.catalog.register(new GatewayProfile(MID, Set.of(Currency.USD), Set.of(), 100, Map.of(), true), never);
            h.catalog.register(new GatewayProfile(PRICEY, Set.of(Currency.EUR), Set.of(CountryCode.of("FR")), 100,
                    Map.of(), true), never);

            AuthorizationResult result = h.authorize("k1");

            assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.NO_GATEWAY_AVAILABLE);
            assertThat(result.attempts()).isEmpty();
            assertThat(result.decision().candidates()).isEmpty();
            assertThat(result.decision().rejections()).containsExactly(
                    new RoutingRejection(CHEAP, RejectionReason.DISABLED),
                    new RoutingRejection(MID, RejectionReason.CURRENCY_NOT_SUPPORTED),
                    new RoutingRejection(PRICEY, RejectionReason.COUNTRY_NOT_SUPPORTED));
            assertThat(never.authorizeCalls()).isZero();
            assertThat(h.idempotency.isCompleted(request("k1").key())).isTrue();
        }

        @Test
        @DisplayName("catálogo vacío: FAILED con NO_GATEWAY_AVAILABLE")
        void emptyCatalog() {
            h = new Harness(CircuitBreakerConfig.of(2, OPEN_DURATION), false);

            AuthorizationResult result = h.authorize("k1");

            assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.payment().failureReason()).contains(AuthorizePaymentService.NO_GATEWAY_AVAILABLE);
        }

        @Test
        @DisplayName("una candidata con perfil pero sin adaptador se salta sin intento")
        void profileWithoutAdapterIsSkipped() {
            h = new Harness(CircuitBreakerConfig.of(2, OPEN_DURATION), false);
            h.catalog.registerProfileOnly(eur(CHEAP, 50));
            FunctionalGateway mid = new FunctionalGateway(MID, h.transactions,
                    a -> GatewayResponse.approved("mid_ref", LATENCY));
            h.catalog.register(eur(MID, 200), mid);

            AuthorizationResult result = h.authorize("k1");

            assertThat(result.decision().candidates()).extracting(RoutingCandidate::gateway)
                    .containsExactly(CHEAP, MID);
            assertThat(result.attempts()).extracting(GatewayAttempt::gateway).containsExactly(MID);
            assertThat(result.payment().authorizedBy()).contains(MID);
        }

        @Test
        @DisplayName("importe enorme (Long.MAX_VALUE céntimos): se enruta y autoriza sin desbordar")
        void hugeAmount() {
            h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_big", LATENCY));

            AuthorizationResult result = h.authorize(Harness.request("big", Long.MAX_VALUE));

            assertThat(result.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.payment().amount()).isEqualTo(Money.of(Long.MAX_VALUE, Currency.EUR));
        }

        @Test
        @DisplayName("BUG: una excepción comprobada lanzada por el adaptador (sneaky throw) debe contar como ERROR ADAPTER_EXCEPTION y hacer failover")
        void checkedExceptionFromAdapterIsAnError() {
            fourGateways(
                    () -> {
                        throw FunctionalGateway.sneakyThrow(new IOException("connection reset"));
                    },
                    () -> GatewayResponse.approved("mid_ref", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY),
                    () -> GatewayResponse.approved("never", LATENCY));

            AuthorizationResult result;
            try {
                result = h.authorize("k1");
            } catch (Throwable t) {
                Payment stuck = h.payments.all().getFirst();
                throw new AssertionError("La excepción del adaptador (" + t + ") se ha escapado del caso de uso: el pago "
                        + "queda en " + stuck.status() + " y la clave completada=" + h.idempotency.isCompleted(
                        request("k1").key()) + " (cualquier reintento dará IDEMPOTENCY_IN_PROGRESS para siempre)", t);
            }

            assertThat(result.attempts()).extracting(GatewayAttempt::outcome, GatewayAttempt::code)
                    .containsExactly(tuple(AttemptOutcome.ERROR, Optional.of(ServiceSupport.ADAPTER_EXCEPTION)),
                            tuple(AttemptOutcome.APPROVED, Optional.empty()));
            assertThat(result.payment().authorizedBy()).contains(MID);
        }
    }

    /** Respuesta de una pasarela que puede lanzar. */
    @FunctionalInterface
    private interface GatewayResponseOrThrow {
        GatewayResponse get();
    }

    // ---------------------------------------------------------------------------------

    @Nested
    @DisplayName("breakers durante la autorización")
    class Breakers {

        private void openStripe() {
            for (int i = 0; i < Harness.FAILURE_THRESHOLD; i++) {
                h.stripe.on(AUTHORIZE, GatewayResponse.error("x", LATENCY));
                h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_open_" + i, LATENCY));
                h.authorize("open-" + i);
            }
            assertThat(h.breakers.peek(STRIPE).state()).isEqualTo(CircuitState.OPEN);
        }

        @Test
        @DisplayName("si tryAcquirePermission sale false (otra petición se lleva la prueba entre la decisión y la llamada) se salta esa candidata")
        void skipsCandidateWhenTrialIsTakenByAnotherRequest() {
            openStripe();
            h.clock.advance(OPEN_DURATION);
            int stripeCallsBefore = h.stripe.calls().size();
            // La otra petición se lleva la prueba justo cuando esta busca el adaptador de Stripe,
            // después de que la política haya decidido con Stripe en HALF_OPEN y libre.
            h.catalog.onNextLookup(STRIPE, () -> {
                CircuitBreaker breaker = h.breakers.peek(STRIPE);
                synchronized (breaker) {
                    assertThat(breaker.tryAcquirePermission(h.clock.instant())).isTrue();
                }
            });
            h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_ok", LATENCY));

            AuthorizationResult result = h.authorize("k-trial");

            assertThat(result.decision().candidates()).extracting(RoutingCandidate::gateway, RoutingCandidate::circuitState)
                    .containsExactly(tuple(STRIPE, CircuitState.HALF_OPEN), tuple(ADYEN, CircuitState.CLOSED));
            assertThat(h.stripe.calls()).hasSize(stripeCallsBefore);
            assertThat(result.attempts()).extracting(GatewayAttempt::gateway).containsExactly(ADYEN);
            assertThat(result.payment().authorizedBy()).contains(ADYEN);
            // La prueba sigue siendo de la otra petición: esta no la ha gastado ni liberado.
            assertThat(h.breakers.peek(STRIPE).snapshot().state()).isEqualTo(CircuitState.HALF_OPEN);
            assertThat(h.breakers.peek(STRIPE).snapshot().trialsInFlight()).isEqualTo(1);
        }

        @Test
        @DisplayName("una prueba de HALF_OPEN perdida caduca tras openDuration y la siguiente autorización vuelve a probar (y cierra)")
        void lostTrialExpires() {
            openStripe();
            h.clock.advance(OPEN_DURATION);
            CircuitBreaker breaker = h.breakers.peek(STRIPE);
            synchronized (breaker) {
                assertThat(breaker.tryAcquirePermission(h.clock.instant())).isTrue(); // petición que se pierde
            }

            // Con la prueba en vuelo, Stripe queda descartada.
            h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_a", LATENCY));
            AuthorizationResult blocked = h.authorize("k-blocked");
            assertThat(blocked.decision().rejections()).contains(new RoutingRejection(STRIPE, RejectionReason.CIRCUIT_OPEN));
            assertThat(blocked.attempts()).extracting(GatewayAttempt::gateway).containsExactly(ADYEN);

            h.clock.advance(OPEN_DURATION);
            h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_back", LATENCY));
            AuthorizationResult probe = h.authorize("k-probe");

            assertThat(probe.attempts()).extracting(GatewayAttempt::gateway).containsExactly(STRIPE);
            assertThat(probe.payment().authorizedBy()).contains(STRIPE);
            assertThat(h.breakers.peek(STRIPE).state()).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        @DisplayName("la prueba de HALF_OPEN que falla reabre el circuito, publica CircuitOpened y hace failover en la misma petición")
        void failedTrialReopens() {
            openStripe();
            h.clock.advance(OPEN_DURATION);
            h.stripe.on(AUTHORIZE, GatewayResponse.timeout(LATENCY));
            h.adyen.on(AUTHORIZE, GatewayResponse.approved("psp_x", LATENCY));
            int eventsBefore = h.events.published().size();

            AuthorizationResult result = h.authorize("k-reopen");

            assertThat(result.attempts()).extracting(GatewayAttempt::gateway, GatewayAttempt::outcome)
                    .containsExactly(tuple(STRIPE, AttemptOutcome.TIMEOUT), tuple(ADYEN, AttemptOutcome.APPROVED));
            assertThat(h.breakers.peek(STRIPE).state()).isEqualTo(CircuitState.OPEN);
            List<DomainEvent> newEvents = h.events.published().subList(eventsBefore, h.events.published().size());
            assertThat(newEvents).filteredOn(e -> e instanceof CircuitHalfOpened || e instanceof CircuitOpened
                            || e instanceof CircuitClosed)
                    .extracting(e -> e.getClass().getSimpleName())
                    .containsExactly("CircuitHalfOpened", "CircuitOpened");
            assertThat(h.events.published(CircuitOpened.class)).hasSize(2);
        }

        @Test
        @DisplayName("eventos de T2: los del pago y los de los breakers, cada uno una vez, sin dejar ninguno pendiente")
        void eventsArePublishedExactlyOnce() {
            openStripe();
            h.clock.advance(OPEN_DURATION);
            h.stripe.on(AUTHORIZE, GatewayResponse.approved("ch_back", LATENCY));
            int eventsBefore = h.events.published().size();

            AuthorizationResult result = h.authorize("k-close");

            List<DomainEvent> newEvents = h.events.published().subList(eventsBefore, h.events.published().size());
            assertThat(newEvents).extracting(e -> e.getClass().getSimpleName()).containsExactly(
                    "PaymentCreated", "GatewayAttemptRecorded", "PaymentAuthorized", "CircuitHalfOpened",
                    "CircuitClosed");
            assertThat(newEvents).filteredOn(GatewayAttemptRecorded.class::isInstance)
                    .singleElement()
                    .satisfies(e -> assertThat(((GatewayAttemptRecorded) e).paymentId()).isEqualTo(result.payment().id()));
            assertNoDuplicateInstances(h.events.published());
            assertThat(h.breakers.peek(STRIPE).pullEvents()).isEmpty();
            assertThat(h.breakers.peek(ADYEN).pullEvents()).isEmpty();
            // En total: un CircuitOpened (al abrir) y nada repetido en toda la historia.
            assertThat(h.events.published(CircuitOpened.class)).hasSize(1);
            assertThat(h.events.published(PaymentCreated.class)).hasSize(Harness.FAILURE_THRESHOLD + 1);
            assertThat(h.events.published(PaymentAuthorized.class)).hasSize(Harness.FAILURE_THRESHOLD + 1);
        }

        @Test
        @DisplayName("un DECLINED en HALF_OPEN cuenta como éxito técnico: cierra el circuito")
        void declinedClosesHalfOpen() {
            openStripe();
            h.clock.advance(OPEN_DURATION);
            h.stripe.on(AUTHORIZE, GatewayResponse.declined("stolen_card", LATENCY));

            AuthorizationResult result = h.authorize("k-dec");

            assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(h.breakers.peek(STRIPE).state()).isEqualTo(CircuitState.CLOSED);
            assertThat(h.adyen.calls()).hasSize(Harness.FAILURE_THRESHOLD);
        }
    }

    static void assertNoDuplicateInstances(List<DomainEvent> events) {
        Map<DomainEvent, Integer> seen = new IdentityHashMap<>();
        List<DomainEvent> duplicated = new ArrayList<>();
        for (DomainEvent e : events) {
            if (seen.merge(e, 1, Integer::sum) == 2) {
                duplicated.add(e);
            }
        }
        assertThat(duplicated).as("eventos publicados dos veces").isEmpty();
        // Y tampoco dos eventos iguales por valor (mismo hecho publicado dos veces desde copias).
        assertThat(Collections.frequency(events, null)).isZero();
        assertThat(events).doesNotHaveDuplicates();
    }
}
