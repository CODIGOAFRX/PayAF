package dev.pedrogomez.payaf.adapter.out.persistencememory;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link CircuitBreakerStore} en memoria: un breaker por pasarela, creado cerrado con la
 * {@link CircuitBreakerConfig} inyectada la primera vez que se pide.
 * <p>
 * Devuelve <strong>siempre la misma instancia</strong> para una pasarela
 * ({@link ConcurrentMap#computeIfAbsent} es atómico): {@link CircuitBreaker} no es seguro para
 * hilos y los servicios serializan sus cambios sincronizándose sobre ella, así que dos
 * instancias para la misma pasarela romperían esa exclusión. Por lo mismo, {@link #save} no
 * necesita copiar nada: el estado ya está en la instancia compartida.
 */
public final class InMemoryCircuitBreakerStore implements CircuitBreakerStore {

    private final CircuitBreakerConfig config;
    private final ConcurrentMap<GatewayId, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public InMemoryCircuitBreakerStore(CircuitBreakerConfig config) {
        this.config = Objects.requireNonNull(config, "la configuración no puede ser nula");
    }

    @Override
    public CircuitBreaker get(GatewayId gateway) {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        return breakers.computeIfAbsent(gateway, id -> new CircuitBreaker(id, config));
    }

    /**
     * @throws IllegalArgumentException si se intenta guardar otra instancia distinta de la que
     *                                  ya hay para esa pasarela
     */
    @Override
    public void save(CircuitBreaker breaker) {
        Objects.requireNonNull(breaker, "el breaker no puede ser nulo");
        CircuitBreaker known = breakers.putIfAbsent(breaker.gateway(), breaker);
        if (known != null && known != breaker) {
            throw new IllegalArgumentException("Ya hay otra instancia del breaker de " + breaker.gateway());
        }
    }

    public CircuitBreakerConfig config() {
        return config;
    }
}
