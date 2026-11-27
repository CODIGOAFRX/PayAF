package dev.pedrogomez.payaf.demo;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.EventPublisher;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.event.CircuitClosed;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Deja en CLOSED y sin fallos los breakers de las pasarelas, para el botón «Restaurar demo».
 * <p>
 * <strong>Por qué no hace falta un método nuevo en ningún puerto.</strong> Cerrar un breaker a mano
 * no es un caso de uso del negocio, así que no va en la aplicación. Pero tampoco hace falta tocar los
 * almacenes: se hace con el puerto {@link CircuitBreakerStore} tal como está y con la máquina de
 * estados pública del dominio, sobre la misma instancia que comparten las peticiones (el contrato del
 * almacén) y con su cerrojo, igual que los servicios. Sustituir la instancia rompería ese contrato a
 * una petición en vuelo.
 * <p>
 * El recorrido es el del propio dominio: un OPEN se adelanta hasta su {@code retryAt} (pasa a
 * HALF_OPEN) y un éxito lo cierra; un CLOSED con fallos acumulados se queda a cero con un éxito. Los
 * eventos de ese recorrido sintético se descartan (el de HALF_OPEN llevaría una hora futura) y se
 * publica un único {@link CircuitClosed} con la hora real. Los eventos que el breaker ya tuviera
 * pendientes se publican también, para no perderlos.
 */
public final class BreakerReset {

    private final CircuitBreakerStore breakers;
    private final EventPublisher events;
    private final TransactionRunner transactions;
    private final Clock clock;

    public BreakerReset(CircuitBreakerStore breakers, EventPublisher events, TransactionRunner transactions,
                        Clock clock) {
        this.breakers = Objects.requireNonNull(breakers);
        this.events = Objects.requireNonNull(events);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public void closeAll(Collection<GatewayId> gateways) {
        Instant now = clock.instant();
        List<DomainEvent> toPublish = new ArrayList<>();
        for (GatewayId id : gateways) {
            CircuitBreaker breaker = breakers.get(id);
            synchronized (breaker) {
                toPublish.addAll(breaker.pullEvents());
                CircuitState before = breaker.state();
                if (before == CircuitState.OPEN) {
                    breaker.allowsRequest(breaker.snapshot().retryAt().orElse(now));
                }
                breaker.recordSuccess(now);
                breaker.pullEvents();
                breakers.save(breaker);
                if (before != CircuitState.CLOSED) {
                    toPublish.add(new CircuitClosed(id, now));
                }
            }
        }
        if (!toPublish.isEmpty()) {
            transactions.inTransaction(() -> {
                events.publish(toPublish);
                return null;
            });
        }
    }
}
