package dev.pedrogomez.payaf.demo;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limitador en memoria de ventana deslizante: como mucho {@code limit} peticiones por clave (la
 * IP) en cada {@code window}. Guarda los instantes de las últimas peticiones de cada clave y olvida
 * de vez en cuando las claves inactivas, para que la memoria no crezca con cada visitante.
 * <p>
 * Es una protección de la demo pública, no una pieza del producto: con varias réplicas cada una
 * contaría por su cuenta. Es seguro para hilos.
 */
public final class SlidingWindowRateLimiter {

    /** Cada cuántas peticiones se barren las claves inactivas. */
    private static final int SWEEP_EVERY = 256;

    private final int limit;
    private final long windowMs;
    private final Clock clock;
    private final ConcurrentMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    /** Lo que decide el limitador: si pasa y, si no, cuántos segundos faltan para que pueda. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    public SlidingWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser al menos 1: " + limit);
        }
        this.limit = limit;
        this.windowMs = Objects.requireNonNull(window).toMillis();
        this.clock = Objects.requireNonNull(clock);
    }

    public Decision tryAcquire(String key) {
        Objects.requireNonNull(key);
        long now = clock.millis();
        if (sinceSweep.incrementAndGet() >= SWEEP_EVERY) {
            sinceSweep.set(0);
            sweep(now);
        }
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() <= now - windowMs) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                long retryMs = deque.peekFirst() + windowMs - now;
                return new Decision(false, Math.max(1, (retryMs + 999) / 1000));
            }
            deque.addLast(now);
            return new Decision(true, 0);
        }
    }

    public int limit() {
        return limit;
    }

    /** Cuántas claves recuerda ahora mismo (para los tests). */
    int trackedKeys() {
        return hits.size();
    }

    private void sweep(long now) {
        hits.entrySet().removeIf(entry -> {
            Deque<Long> deque = entry.getValue();
            synchronized (deque) {
                return deque.isEmpty() || deque.peekLast() <= now - windowMs;
            }
        });
    }
}
