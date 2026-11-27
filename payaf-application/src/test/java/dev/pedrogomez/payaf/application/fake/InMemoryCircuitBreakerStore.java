package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.util.HashMap;
import java.util.Map;

/**
 * Un breaker por pasarela, siempre la misma instancia, creado con la configuración dada.
 * Si recibe el {@link FakeTransactionRunner}, comprueba el contrato del puerto: no se le
 * llama dentro de una transacción de pagos.
 */
public final class InMemoryCircuitBreakerStore implements CircuitBreakerStore {

    private final CircuitBreakerConfig config;
    private final FakeTransactionRunner transactions;
    private final Map<GatewayId, CircuitBreaker> breakers = new HashMap<>();
    private int saves;
    private volatile java.util.function.Function<GatewayId, Throwable> getFailure = id -> null;
    private volatile java.util.function.Function<CircuitBreaker, Throwable> saveFailure = b -> null;

    public InMemoryCircuitBreakerStore(CircuitBreakerConfig config) {
        this(config, null);
    }

    public InMemoryCircuitBreakerStore(CircuitBreakerConfig config, FakeTransactionRunner transactions) {
        this.config = config;
        this.transactions = transactions;
    }

    @Override
    public synchronized CircuitBreaker get(GatewayId gateway) {
        if (transactions != null) {
            transactions.requireNoTransaction("CircuitBreakerStore.get(" + gateway + ")");
        }
        Throwable failure = getFailure.apply(gateway);
        if (failure != null) {
            throw FunctionalGateway.sneakyThrow(failure);
        }
        return breakers.computeIfAbsent(gateway, id -> new CircuitBreaker(id, config));
    }

    @Override
    public synchronized void save(CircuitBreaker breaker) {
        if (transactions != null) {
            transactions.requireNoTransaction("CircuitBreakerStore.save(" + breaker.gateway() + ")");
        }
        Throwable failure = saveFailure.apply(breaker);
        if (failure != null) {
            throw FunctionalGateway.sneakyThrow(failure);
        }
        CircuitBreaker known = breakers.get(breaker.gateway());
        if (known != null && known != breaker) {
            throw new AssertionError("Se ha guardado otra instancia del breaker de " + breaker.gateway());
        }
        breakers.put(breaker.gateway(), breaker);
        saves++;
    }

    /** Si la función devuelve algo, {@link #get} lo lanza (simula un almacén que falla). */
    public void failGetWhen(java.util.function.Function<GatewayId, Throwable> failure) {
        this.getFailure = failure;
    }

    /** Si la función devuelve algo, {@link #save} lo lanza sin guardar (simula un almacén que falla). */
    public void failSaveWhen(java.util.function.Function<CircuitBreaker, Throwable> failure) {
        this.saveFailure = failure;
    }

    /** El breaker sin pasar por las comprobaciones del puerto, para preparar y comprobar tests. */
    public synchronized CircuitBreaker peek(GatewayId gateway) {
        return breakers.computeIfAbsent(gateway, id -> new CircuitBreaker(id, config));
    }

    public synchronized int saves() {
        return saves;
    }
}
