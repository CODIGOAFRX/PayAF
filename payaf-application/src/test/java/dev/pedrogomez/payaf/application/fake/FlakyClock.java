package dev.pedrogomez.payaf.application.fake;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reloj que delega en un {@link MutableClock} y lanza una vez, en la llamada número
 * {@code failAt} a {@link #instant()} (contando desde 1). Como los servicios leen el reloj en
 * casi cada paso, recorrer {@code failAt} de 1 a N inyecta un fallo en cada punto del
 * enrutado sin tocar el código de producción.
 */
public final class FlakyClock extends Clock {

    private final MutableClock delegate;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int failAt = -1;
    /** Si es true, a partir de {@code failAt} falla en todas las lecturas, no solo en esa. */
    private volatile boolean permanent;
    private volatile RuntimeException failure = new IllegalStateException("reloj caído");

    public FlakyClock(MutableClock delegate) {
        this.delegate = delegate;
    }

    /** Falla en la llamada {@code n} a partir de ahora (1 = la siguiente). */
    public FlakyClock failOnCall(int n, RuntimeException failure) {
        this.failure = failure;
        this.failAt = calls.get() + n;
        this.permanent = false;
        return this;
    }

    /** Falla en la llamada {@code n} a partir de ahora y en todas las siguientes, hasta {@link #heal()}. */
    public FlakyClock failFromCall(int n, RuntimeException failure) {
        failOnCall(n, failure);
        this.permanent = true;
        return this;
    }

    /** Vuelve a funcionar. */
    public FlakyClock heal() {
        this.failAt = -1;
        this.permanent = false;
        return this;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public Instant instant() {
        int call = calls.incrementAndGet();
        int at = failAt;
        if (at > 0 && (call == at || (permanent && call > at))) {
            throw failure;
        }
        return delegate.instant();
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
