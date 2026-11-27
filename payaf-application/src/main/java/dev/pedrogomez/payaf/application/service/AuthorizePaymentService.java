package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore.Reservation;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.application.port.out.PaymentIdGenerator;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.payment.RequestFingerprint;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.routing.RoutingCandidate;
import dev.pedrogomez.payaf.domain.routing.RoutingDecision;
import dev.pedrogomez.payaf.domain.routing.RoutingPolicy;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Caso de uso «autorizar un pago»: idempotencia, enrutado y failover. Es el corazón de PayAF.
 * <p>
 * <strong>Algoritmo</strong>
 * <ol>
 *   <li><strong>Reserva (transacción 1).</strong> Se toma {@code now} del reloj y un
 *       identificador candidato, y se intenta reservar la clave de idempotencia
 *       ({@link IdempotencyStore#tryReserve}, atómico):
 *       <ul>
 *         <li>Si la clave ya existe con <em>otra</em> huella, en cualquier estado:
 *             IDEMPOTENCY_CONFLICT. El comercio está reutilizando la clave para otra orden.</li>
 *         <li>{@code AlreadyCompleted} con la misma huella: se devuelve el pago guardado con
 *             {@code replayed = true} y no se llama a ninguna pasarela. Así un reintento de
 *             red del comercio nunca cobra dos veces.</li>
 *         <li>{@code InProgress} con la misma huella: IDEMPOTENCY_IN_PROGRESS. Otra petición
 *             está enrutando ese mismo pago; esperar o devolver un PENDING a medias sería
 *             peor que pedir al comercio que reintente.</li>
 *         <li>{@code Reserved}: se crea el pago en PENDING, se guarda y se publican sus
 *             eventos en esa misma transacción. Desde aquí el pago existe y es consultable.</li>
 *       </ul></li>
 *   <li><strong>Enrutado y failover (sin transacción).</strong> La política de enrutado del
 *       dominio ordena las candidatas con los breakers del {@link CircuitBreakerStore}. Para
 *       cada una, en orden:
 *       <ul>
 *         <li>Se reserva permiso en su breaker ({@code tryAcquirePermission}); si no se
 *             concede (otra petición se ha llevado la prueba de HALF_OPEN), se salta.</li>
 *         <li>Se llama a la pasarela, se registra el intento en el pago y el resultado en el
 *             breaker, y se guarda el breaker.</li>
 *         <li>APPROVED: se autoriza el pago y se termina.</li>
 *         <li>DECLINED: el pago falla con {@code "DECLINED: <código>"} y se termina.
 *             <strong>No hay failover en un rechazo</strong>: es la decisión del emisor de la
 *             tarjeta (fondos, fraude, tarjeta bloqueada), no un fallo de la pasarela.
 *             Reintentar en otra PSP sería intentar cobrar a escondidas algo que el titular o
 *             su banco ya han rechazado, y además se pagaría otra comisión por nada.</li>
 *         <li>ERROR o TIMEOUT: fallo técnico, se pasa a la siguiente candidata (failover).</li>
 *       </ul>
 *       Si no hay candidatas o todas fallan técnicamente, el pago falla con
 *       {@value #NO_GATEWAY_AVAILABLE}.</li>
 *   <li><strong>Cierre (transacción 2).</strong> Se guarda el pago, se publican sus eventos y
 *       los de los breakers afectados, y se marca la clave como completada.</li>
 *   <li>Se devuelve el resultado con la decisión y los intentos.</li>
 * </ol>
 * <p>
 * <strong>Por qué nunca se llama a una pasarela con una transacción abierta.</strong> Una
 * llamada a una PSP puede tardar segundos (o agotar su tiempo de espera). Con la transacción
 * abierta, cada pago en vuelo retendría una conexión del pool y sus bloqueos de fila: con
 * unas pocas pasarelas lentas se agotaría el pool y caería todo el servicio, no solo el
 * pago. Además, la transacción no puede deshacer lo que pasa en la PSP: si se deshiciera
 * después de que la pasarela aprobara, perderíamos el rastro de una autorización real. Por
 * eso hay dos transacciones cortas, antes y después, y el pago queda en PENDING (guardado y
 * con su clave reservada) mientras se habla con las pasarelas.
 * <p>
 * <strong>Errores inesperados.</strong> Si algo falla durante el enrutado (una excepción del
 * adaptador que se escape, un desbordamiento en la política), el pago no se queda en PENDING:
 * falla con {@value #ROUTING_ERROR}, se cierra con la transacción 2 y se devuelve, sin
 * relanzar. Los {@link Error} de la JVM sí se propagan; los PENDING huérfanos que dejen los
 * recogerá un proceso de limpieza en la fase 4.
 * <p>
 * <strong>Tiempo.</strong> Cada paso toma un instante fresco del reloj, nunca anterior a la
 * última actualización del pago, para respetar el tiempo monótono del agregado.
 * <p>
 * <strong>Replay.</strong> La decisión que se devuelve en una repetición es vacía (ver
 * {@link AuthorizationResult}): la original no se guarda y volver a ejecutar la política
 * daría la decisión de ahora (con los breakers de ahora), además de hacer avanzar breakers
 * en una operación que debe ser de solo lectura.
 */
public final class AuthorizePaymentService implements AuthorizePaymentUseCase {

    /** Motivo de fallo cuando ninguna pasarela ha podido atender el pago. */
    public static final String NO_GATEWAY_AVAILABLE = "NO_GATEWAY_AVAILABLE";

    /** Motivo de fallo cuando el propio enrutado ha fallado por un error inesperado (ver {@code route}). */
    public static final String ROUTING_ERROR = "ROUTING_ERROR";

    /** Prefijo del motivo de fallo cuando el emisor rechaza el pago. */
    public static final String DECLINED_PREFIX = "DECLINED";

    private static final RoutingDecision NO_DECISION = new RoutingDecision(List.of(), List.of());

    private final PaymentRepository payments;
    private final IdempotencyStore idempotency;
    private final GatewayCatalog catalog;
    private final CircuitBreakerStore breakerStore;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactions;
    private final PaymentIdGenerator idGenerator;
    private final Clock clock;
    private final RoutingPolicy routingPolicy = new RoutingPolicy();

    public AuthorizePaymentService(PaymentRepository payments, IdempotencyStore idempotency, GatewayCatalog catalog,
                                   CircuitBreakerStore breakerStore, EventPublisher eventPublisher,
                                   TransactionRunner transactions, PaymentIdGenerator idGenerator, Clock clock) {
        this.payments = Objects.requireNonNull(payments);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.catalog = Objects.requireNonNull(catalog);
        this.breakerStore = Objects.requireNonNull(breakerStore);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.transactions = Objects.requireNonNull(transactions);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AuthorizationResult authorize(AuthorizePaymentCommand command) {
        Objects.requireNonNull(command, "el comando no puede ser nulo");
        PaymentRequest request = command.request();

        // 1. Reserva de la clave y creación del pago (transacción 1).
        Start start = reserve(request);
        if (start.replayed()) {
            Payment stored = start.payment();
            return new AuthorizationResult(PaymentView.from(stored), NO_DECISION, stored.attempts(), true);
        }
        Payment payment = start.payment();

        // 2. Enrutado y failover, sin transacción.
        Map<GatewayId, CircuitBreaker> breakers = new LinkedHashMap<>();
        RoutingProgress progress = route(payment, breakers);

        if (progress.unrecordedAuthorization != null) {
            closeUnrecordedAuthorization(payment, progress.unrecordedAuthorization);
        }

        // 3. Cierre (transacción 2).
        // Los eventos de los breakers se sacan aquí y se guarda la copia en el progreso. Si la
        // transacción se deshace (falla el outbox, por ejemplo), esos eventos ya han salido del
        // breaker y el dominio no permite devolverlos: se pierden. Es un hueco conocido que se
        // cerrará en la fase 4 con el outbox (los eventos de breaker son informativos para el
        // panel; el estado del breaker sí está guardado y no se pierde).
        progress.pulledBreakerEvents = ServiceSupport.pullEvents(breakers.values());
        transactions.inTransaction(() -> {
            payments.save(payment);
            List<DomainEvent> events = new ArrayList<>(payment.pullEvents());
            events.addAll(progress.pulledBreakerEvents);
            eventPublisher.publish(events);
            idempotency.complete(request.key(), payment.id());
            return null;
        });

        // 4. Resultado.
        return new AuthorizationResult(PaymentView.from(payment), progress.decision, payment.attempts(), false);
    }

    /**
     * La PSP aprobó pero no ha sido posible apuntar la autorización en el pago. Se guarda el
     * pago tal como está (PENDING, con los intentos que se hayan podido registrar), sin
     * publicar eventos ni completar la clave, y se avisa con AUTHORIZATION_UNRECORDED.
     * <p>
     * <strong>Marcarlo FAILED sería mentir:</strong> en la PSP hay dinero retenido de verdad. Un
     * FAILED le diría al comercio que no se ha cobrado nada (y quizá reintentaría con otra
     * clave, reteniendo dos veces), y nadie anularía esa autorización. PENDING con la clave en
     * curso es la verdad: el resultado está pendiente de reconciliar. El reaper o la
     * reconciliación con la PSP de la fase 4 lo resolverán con la referencia que va en el error.
     */
    private void closeUnrecordedAuthorization(Payment payment, UnrecordedAuthorization unrecorded) {
        transactions.inTransaction(() -> {
            payments.save(payment);
            return null;
        });
        throw ApplicationException.authorizationUnrecorded(payment.id(), unrecorded.gateway(),
                unrecorded.gatewayReference());
    }

    /** Lo que sale de la transacción 1: el pago recién creado o el ya completado. */
    private record Start(Payment payment, boolean replayed) {
    }

    private Start reserve(PaymentRequest request) {
        IdempotencyKey key = request.key();
        RequestFingerprint fingerprint = request.fingerprint();
        Instant now = clock.instant();
        PaymentId candidateId = idGenerator.next();

        return transactions.inTransaction(() -> {
            Reservation reservation = idempotency.tryReserve(key, fingerprint, candidateId, now);
            return switch (reservation) {
                case Reservation.Reserved reserved -> {
                    Payment created = Payment.create(candidateId, request, now);
                    payments.save(created);
                    eventPublisher.publish(created.pullEvents());
                    yield new Start(created, false);
                }
                case Reservation.AlreadyCompleted completed -> {
                    requireSameFingerprint(key, fingerprint, completed.fingerprint());
                    Payment stored = payments.findById(completed.paymentId())
                            .orElseThrow(() -> new IllegalStateException("La clave «" + key
                                    + "» está completada pero su pago " + completed.paymentId() + " no existe"));
                    yield new Start(stored, true);
                }
                case Reservation.InProgress inProgress -> {
                    requireSameFingerprint(key, fingerprint, inProgress.fingerprint());
                    throw ApplicationException.idempotencyInProgress(key, inProgress.paymentId());
                }
            };
        });
    }

    private static void requireSameFingerprint(IdempotencyKey key, RequestFingerprint received,
                                               RequestFingerprint stored) {
        if (!received.equals(stored)) {
            throw ApplicationException.idempotencyConflict(key);
        }
    }

    /** Autorización aprobada por la PSP que no se ha podido apuntar en el pago. */
    private record UnrecordedAuthorization(GatewayId gateway, String gatewayReference) {
    }

    /** Lo que lleva hecho el enrutado, para que la red de seguridad sepa qué recoger si algo se escapa. */
    private static final class RoutingProgress {
        RoutingDecision decision = NO_DECISION;
        /** Breaker con permiso concedido cuyo resultado aún no se ha registrado en él. */
        CircuitBreaker permitWithoutResult;
        /** Pasarela de la última llamada y su respuesta. */
        GatewayId lastGateway;
        GatewayResponse lastResponse;
        /** Si la última respuesta ya está registrada como intento en el pago. */
        boolean lastAttemptRecorded;
        /** Si la red no consigue apuntar un APPROVED, aquí queda para avisar. */
        UnrecordedAuthorization unrecordedAuthorization;
        /** Eventos de breaker ya sacados para la transacción 2 (ver {@code authorize}). */
        List<DomainEvent> pulledBreakerEvents = List.of();
    }

    /**
     * Decide el orden de las pasarelas y las prueba hasta que una responde. Deja el pago en
     * AUTHORIZED o FAILED y en {@code breakers} los breakers consultados, para publicar sus
     * eventos después.
     * <p>
     * <strong>Red de seguridad.</strong> Si durante el enrutado se escapa cualquier
     * {@link Exception} (un fallo nuestro o del dominio, como un desbordamiento al calcular la
     * comisión de un importe gigantesco, un reloj o un almacén de breakers que fallan), no se
     * relanza. Se recoge lo que se sabe:
     * <ol>
     *   <li>Si la última pasarela respondió y su intento no quedó registrado en el pago, se
     *       registra.</li>
     *   <li>Si había un permiso de breaker sin resultado registrado, se registra en él el
     *       resultado <em>real</em> de la última respuesta (un APPROVED es un éxito; un breaker
     *       que respondió bien no se penaliza). Si la pasarela ni siquiera llegó a responder,
     *       se registra como fallo: el dominio no ofrece «devolver» una prueba de HALF_OPEN, y
     *       un intento cuyo resultado no sabemos se parece a un TIMEOUT.</li>
     *   <li>Si el pago sigue en PENDING y la última respuesta fue APPROVED, se autoriza: la PSP
     *       tiene el dinero retenido y el pago tiene que decirlo. Si ni así se puede, el pago
     *       se queda PENDING y se lanza AUTHORIZATION_UNRECORDED (ver
     *       {@link #closeUnrecordedAuthorization}).</li>
     *   <li>Si el pago sigue en PENDING por cualquier otra razón, falla con
     *       {@value #ROUTING_ERROR}. Si ya estaba autorizado o rechazado, se respeta.</li>
     * </ol>
     * Después se sigue con la transacción 2 normal y se devuelve el pago: la clave no se queda
     * en curso para siempre y un reintento del comercio recibe la repetición idempotente.
     * Los instantes de la recuperación se toman del reloj y, si el reloj es lo que falla, se
     * usa la última actualización del pago, que siempre es válida para el agregado.
     * <p>
     * Los {@link Error} (memoria agotada...) se dejan pasar: son fallos de la JVM y no hay
     * garantías de poder seguir. En ese caso el pago queda en PENDING con la clave en curso;
     * recuperar esos huérfanos es tarea de un proceso de limpieza (fase 4).
     */
    private RoutingProgress route(Payment payment, Map<GatewayId, CircuitBreaker> breakers) {
        RoutingProgress progress = new RoutingProgress();
        try {
            routeUnguarded(payment, breakers, progress);
        } catch (Exception e) {
            recoverFromRoutingFailure(payment, progress);
        }
        return progress;
    }

    private void recoverFromRoutingFailure(Payment payment, RoutingProgress progress) {
        GatewayResponse last = progress.lastResponse;

        // 1. El intento de la última respuesta, si no quedó registrado.
        if (last != null && !progress.lastAttemptRecorded && payment.status() == PaymentStatus.PENDING) {
            try {
                payment.recordAttempt(new GatewayAttempt(progress.lastGateway, last.outcome(), last.latency(),
                        last.code(), last.gatewayReference(), safeInstant(payment)));
                progress.lastAttemptRecorded = true;
            } catch (Exception ignored) {
                // Se pierde el detalle del intento, pero no lo que importa: el estado del pago.
            }
        }

        // 2. El breaker con el permiso pendiente, con el resultado real si lo hay.
        CircuitBreaker pending = progress.permitWithoutResult;
        if (pending != null) {
            AttemptOutcome outcome = last != null && pending.gateway().equals(progress.lastGateway)
                    ? last.outcome()
                    : AttemptOutcome.ERROR;
            try {
                Instant now = safeInstant(payment);
                synchronized (pending) {
                    pending.record(outcome, now);
                    progress.permitWithoutResult = null;
                    breakerStore.save(pending);
                }
            } catch (Exception ignored) {
                // Si ni siquiera se puede registrar, la prueba caducará sola (ver CircuitBreaker).
            }
        }

        if (payment.status() != PaymentStatus.PENDING) {
            return;
        }
        // 3. Un APPROVED de la PSP no se convierte nunca en FAILED.
        if (last != null && last.isApproved()) {
            String reference = last.gatewayReference().orElseThrow();
            try {
                payment.authorize(progress.lastGateway, reference, safeInstant(payment));
            } catch (Exception e) {
                progress.unrecordedAuthorization = new UnrecordedAuthorization(progress.lastGateway, reference);
            }
            return;
        }
        // 4. Cualquier otro caso: el enrutado ha fallado.
        payment.fail(ROUTING_ERROR, safeInstant(payment));
    }

    /** Instante para la recuperación: el del reloj si se puede leer, y nunca anterior al del pago. */
    private Instant safeInstant(Payment payment) {
        try {
            return ServiceSupport.nextInstant(clock, payment);
        } catch (Exception e) {
            return payment.updatedAt();
        }
    }

    private void routeUnguarded(Payment payment, Map<GatewayId, CircuitBreaker> breakers,
                                RoutingProgress progress) {
        List<GatewayProfile> profiles = catalog.profiles();
        for (GatewayProfile profile : profiles) {
            breakers.put(profile.id(), breakerStore.get(profile.id()));
        }
        RoutingDecision decision = decide(payment.request(), profiles, breakers);
        progress.decision = decision;

        for (RoutingCandidate candidate : decision.candidates()) {
            Optional<PaymentGateway> gateway = catalog.gateway(candidate.gateway());
            if (gateway.isEmpty()) {
                // Tiene perfil pero no adaptador: no se puede intentar, y no se gasta su prueba.
                continue;
            }
            CircuitBreaker breaker = breakers.get(candidate.gateway());
            if (!ServiceSupport.tryAcquire(breaker, clock.instant())) {
                // Otra petición se ha llevado la prueba de HALF_OPEN (o se acaba de abrir).
                continue;
            }
            progress.permitWithoutResult = breaker;
            progress.lastGateway = candidate.gateway();
            progress.lastResponse = null;
            progress.lastAttemptRecorded = false;

            GatewayResponse response = ServiceSupport.callSafely(clock,
                    () -> gateway.get().authorize(GatewayAuthorization.of(payment)));
            progress.lastResponse = response;
            payment.recordAttempt(new GatewayAttempt(candidate.gateway(), response.outcome(), response.latency(),
                    response.code(), response.gatewayReference(), ServiceSupport.nextInstant(clock, payment)));
            progress.lastAttemptRecorded = true;

            // Primero se fija el pago y después el breaker: si guardar el breaker fallara, un
            // pago que la PSP ya ha autorizado no puede acabar FAILED por la red de seguridad.
            boolean settled = switch (response.outcome()) {
                case APPROVED -> {
                    payment.authorize(candidate.gateway(), response.gatewayReference().orElseThrow(),
                            ServiceSupport.nextInstant(clock, payment));
                    yield true;
                }
                case DECLINED -> {
                    payment.fail(declineReason(response), ServiceSupport.nextInstant(clock, payment));
                    yield true;
                }
                // Fallo técnico: failover a la siguiente candidata.
                case ERROR, TIMEOUT -> false;
            };
            Instant recordedAt = clock.instant();
            synchronized (breaker) {
                breaker.record(response.outcome(), recordedAt);
                // El permiso se da por resuelto justo después de registrar el resultado, y no
                // antes: si record fallara, la red de seguridad aún sabría que la prueba está
                // pendiente. Si lo que falla es el save, el resultado ya está en el breaker.
                progress.permitWithoutResult = null;
                breakerStore.save(breaker);
            }
            if (settled) {
                return;
            }
        }
        payment.fail(NO_GATEWAY_AVAILABLE, ServiceSupport.nextInstant(clock, payment));
    }

    /**
     * Ejecuta la política con los breakers bloqueados y guarda los que la propia decisión
     * haya hecho avanzar (de OPEN a HALF_OPEN), para que el cambio no se pierda en un almacén
     * persistente.
     */
    private RoutingDecision decide(PaymentRequest request, List<GatewayProfile> profiles,
                                   Map<GatewayId, CircuitBreaker> breakers) {
        Instant now = clock.instant();
        return ServiceSupport.withAllLocked(breakers.values(), () -> {
            Map<GatewayId, CircuitState> before = new LinkedHashMap<>();
            breakers.forEach((id, breaker) -> before.put(id, breaker.state()));
            RoutingDecision decision = routingPolicy.decide(request, profiles, breakers, now);
            breakers.forEach((id, breaker) -> {
                if (breaker.state() != before.get(id)) {
                    breakerStore.save(breaker);
                }
            });
            return decision;
        });
    }

    private static String declineReason(GatewayResponse response) {
        return response.code().filter(code -> !code.isBlank())
                .map(code -> DECLINED_PREFIX + ": " + code)
                .orElse(DECLINED_PREFIX);
    }
}
