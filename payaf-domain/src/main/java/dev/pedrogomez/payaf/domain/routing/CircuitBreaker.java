package dev.pedrogomez.payaf.domain.routing;

import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.CircuitHalfOpened;
import dev.pedrogomez.payaf.domain.event.CircuitOpened;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Circuit breaker de una pasarela: deja de mandarle pagos cuando falla de forma repetida
 * y vuelve a probar pasado un tiempo.
 * <p>
 * Es dominio, y no un detalle técnico que se delegue en una librería, porque aquí decide
 * el negocio: qué cuenta como fallo, cuánto se tolera y cuándo se da otra oportunidad a
 * una pasarela forman parte de la política de enrutado, y el panel tiene que poder
 * explicarlo. Además, al ser Java puro y recibir el {@link Instant} siempre como parámetro
 * (nunca lee el reloj), se prueba con instantes fijos y sin esperas.
 * <p>
 * Máquina de estados:
 * <ul>
 *   <li>CLOSED → OPEN cuando se acumulan {@code failureThreshold} fallos técnicos seguidos.</li>
 *   <li>OPEN → HALF_OPEN cuando ha vencido {@code openDuration} y alguien pregunta con
 *       {@link #allowsRequest}. Es perezoso: sin preguntas, el circuito sigue OPEN.</li>
 *   <li>HALF_OPEN → CLOSED con el primer éxito; HALF_OPEN → OPEN con el primer fallo, y
 *       el tiempo de apertura vuelve a contar desde ese fallo.</li>
 * </ul>
 * <p>
 * <strong>Qué cuenta como fallo.</strong> Solo ERROR y TIMEOUT, que son fallos técnicos de la
 * pasarela. DECLINED NO cuenta: es una decisión de negocio del emisor de la tarjeta (fondos
 * insuficientes, sospecha de fraude) que la pasarela ha transmitido correctamente. Si
 * contara, una racha de tarjetas rechazadas abriría el circuito de una pasarela sana y el
 * tráfico se iría a otra que rechazaría igual esas tarjetas. Por eso un DECLINED se trata
 * como un éxito técnico y reinicia el contador ({@link #record(AttemptOutcome, Instant)}).
 * <p>
 * <strong>Consultar frente a reservar.</strong> {@link #allowsRequest} solo pregunta: no
 * gasta la petición de prueba de HALF_OPEN. La usa la política de enrutado, que evalúa
 * todas las pasarelas aunque luego solo se llame a una; si gastara la prueba, una pasarela
 * en HALF_OPEN que no fuera la elegida se quedaría sin prueba y no se recuperaría nunca.
 * Justo antes de llamar de verdad a la pasarela, el caso de uso reserva la prueba con
 * {@link #tryAcquirePermission}.
 * <p>
 * <strong>Pruebas caducadas.</strong> Si se concede una petición de prueba en HALF_OPEN y no
 * llega ningún resultado en {@code openDuration} (la petición se perdió, el proceso que la
 * hacía murió...), la prueba caduca y vuelve a estar disponible. Sin esta regla el circuito
 * se quedaría bloqueado en HALF_OPEN para siempre: ni cerrado ni abierto, sin tráfico y sin
 * forma de recuperarse. Se reutiliza {@code openDuration} como plazo porque es la paciencia
 * que el negocio ya ha decidido tener con esa pasarela. La liberación es perezosa, como el
 * paso de OPEN a HALF_OPEN: la hacen {@link #allowsRequest} y {@link #tryAcquirePermission}
 * con el {@code now} que reciben. Si el resultado de una prueba caducada llega después, se
 * aplica igualmente: en HALF_OPEN cualquier éxito cierra y cualquier fallo abre.
 * <p>
 * Los resultados que llegan con el circuito OPEN (respuestas tardías de peticiones que
 * salieron antes de abrirlo) se ignoran: ni lo cierran ni alargan la apertura.
 * <p>
 * No es seguro para hilos; la aplicación serializa el acceso a cada breaker.
 */
public final class CircuitBreaker {

    private final GatewayId gateway;
    private final CircuitBreakerConfig config;
    private final List<DomainEvent> events = new ArrayList<>();

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    /** Cuándo se concedió cada petición de prueba de HALF_OPEN que aún no tiene resultado. */
    private final List<Instant> trialsAcquiredAt = new ArrayList<>();
    private Instant openedAt;

    /** Crea el breaker de una pasarela, cerrado y sin fallos. */
    public CircuitBreaker(GatewayId gateway, CircuitBreakerConfig config) {
        this.gateway = Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        this.config = Objects.requireNonNull(config, "la configuración no puede ser nula");
    }

    /**
     * ¿Admitiría ahora una petición? CLOSED siempre; OPEN nunca, salvo que haya vencido el
     * tiempo de apertura, en cuyo caso pasa a HALF_OPEN (y emite {@link CircuitHalfOpened});
     * HALF_OPEN mientras queden peticiones de prueba sin conceder.
     * <p>
     * No reserva nada: ver {@link #tryAcquirePermission}. Sí libera, de forma perezosa, las
     * pruebas caducadas (ver el Javadoc de la clase).
     */
    public boolean allowsRequest(Instant now) {
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        if (state == CircuitState.OPEN && !now.isBefore(retryAtInstant())) {
            state = CircuitState.HALF_OPEN;
            trialsAcquiredAt.clear();
            events.add(new CircuitHalfOpened(gateway, now));
        }
        if (state == CircuitState.HALF_OPEN) {
            trialsAcquiredAt.removeIf(acquiredAt -> !now.isBefore(acquiredAt.plus(config.openDuration())));
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> trialsAcquiredAt.size() < config.halfOpenMaxTrials();
        };
    }

    /**
     * Como {@link #allowsRequest}, pero si el circuito está en HALF_OPEN reserva una de las
     * peticiones de prueba. Se llama justo antes de enviar la petición a la pasarela.
     *
     * @return si se puede enviar la petición
     */
    public boolean tryAcquirePermission(Instant now) {
        if (!allowsRequest(now)) {
            return false;
        }
        if (state == CircuitState.HALF_OPEN) {
            trialsAcquiredAt.add(now);
        }
        return true;
    }

    /** La pasarela ha respondido bien (aprobado o rechazado por el emisor). */
    public void recordSuccess(Instant now) {
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        switch (state) {
            case CLOSED -> consecutiveFailures = 0;
            case HALF_OPEN -> close(now);
            case OPEN -> {
                // Respuesta tardía de una petición anterior a la apertura: se ignora.
            }
        }
    }

    /** La pasarela ha fallado técnicamente (ERROR o TIMEOUT). */
    public void recordFailure(Instant now) {
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        switch (state) {
            case CLOSED -> {
                consecutiveFailures++;
                if (consecutiveFailures >= config.failureThreshold()) {
                    open(now);
                }
            }
            case HALF_OPEN -> {
                consecutiveFailures++;
                open(now);
            }
            case OPEN -> {
                // Respuesta tardía de una petición anterior a la apertura: se ignora.
            }
        }
    }

    /**
     * Atajo que traduce el resultado de un intento: ERROR y TIMEOUT son fallos; APPROVED y
     * DECLINED, éxitos técnicos (ver el Javadoc de la clase sobre DECLINED).
     */
    public void record(AttemptOutcome outcome, Instant now) {
        Objects.requireNonNull(outcome, "el resultado no puede ser nulo");
        if (outcome.isTechnicalFailure()) {
            recordFailure(now);
        } else {
            recordSuccess(now);
        }
    }

    private void open(Instant now) {
        state = CircuitState.OPEN;
        openedAt = now;
        trialsAcquiredAt.clear();
        events.add(new CircuitOpened(gateway, now));
    }

    private void close(Instant now) {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        trialsAcquiredAt.clear();
        openedAt = null;
        events.add(new CircuitClosed(gateway, now));
    }

    private Instant retryAtInstant() {
        return openedAt.plus(config.openDuration());
    }

    /** Entrega los eventos acumulados desde la última llamada y vacía la lista. */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    /** Foto del último estado conocido, para el panel. No hace avanzar el estado. */
    public CircuitBreakerSnapshot snapshot() {
        boolean open = state == CircuitState.OPEN;
        return new CircuitBreakerSnapshot(
                gateway,
                state,
                consecutiveFailures,
                open ? Optional.of(openedAt) : Optional.empty(),
                open ? Optional.of(retryAtInstant()) : Optional.empty(),
                trialsAcquiredAt.size());
    }

    /**
     * Foto del estado <em>efectivo</em> en {@code now}: lo que vería una petición que llegara
     * en ese instante, pero sin hacer avanzar el breaker ni emitir eventos. Aplica las mismas
     * reglas perezosas que {@link #allowsRequest}:
     * <ul>
     *   <li>OPEN con el tiempo de apertura vencido se ve como HALF_OPEN sin pruebas en vuelo.</li>
     *   <li>En HALF_OPEN no cuentan las pruebas caducadas (concedidas hace {@code openDuration}
     *       o más sin resultado).</li>
     * </ul>
     * Es lo que necesita el panel: una consulta no debe cambiar el estado compartido.
     */
    public CircuitBreakerSnapshot snapshotAt(Instant now) {
        Objects.requireNonNull(now, "el instante no puede ser nulo");
        if (state == CircuitState.OPEN && !now.isBefore(retryAtInstant())) {
            return new CircuitBreakerSnapshot(gateway, CircuitState.HALF_OPEN, consecutiveFailures,
                    Optional.empty(), Optional.empty(), 0);
        }
        if (state == CircuitState.HALF_OPEN) {
            int live = (int) trialsAcquiredAt.stream()
                    .filter(acquiredAt -> now.isBefore(acquiredAt.plus(config.openDuration())))
                    .count();
            return new CircuitBreakerSnapshot(gateway, CircuitState.HALF_OPEN, consecutiveFailures,
                    Optional.empty(), Optional.empty(), live);
        }
        return snapshot();
    }

    public GatewayId gateway() {
        return gateway;
    }

    public CircuitBreakerConfig config() {
        return config;
    }

    /** Último estado conocido, sin hacerlo avanzar con el reloj. */
    public CircuitState state() {
        return state;
    }
}
