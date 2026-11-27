package dev.pedrogomez.payaf.adapter.out.outbox;

import dev.pedrogomez.payaf.application.port.out.EventStream;
import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link EventStream} que reparte los mensajes del outbox entre suscriptores del mismo proceso
 * (las conexiones SSE del panel).
 * <p>
 * <strong>Un suscriptor lento no frena a nadie.</strong> Cada suscripción tiene su propia cola
 * acotada y su propio hilo virtual, que le va entregando los mensajes. {@link #deliver} solo los
 * encola, así que nunca se bloquea: si la cola de un suscriptor está llena (un navegador con mala
 * conexión, una pestaña dormida), se descarta su mensaje <em>más antiguo</em> y se cuenta en
 * {@link Subscription#dropped()}. Para un panel en directo es lo correcto, porque importa más lo
 * último que lo viejo, y el relay (que llama a {@code deliver} con la transacción abierta) no se
 * queda esperando a nadie.
 * <p>
 * Si el consumidor de un suscriptor lanza una excepción, se cierra esa suscripción y las demás
 * siguen. Es seguro para hilos.
 * <p>
 * Con varias réplicas, cada una reparte solo lo que lee su relay: un panel conectado a una
 * réplica no vería los eventos que releyó otra. Para la demo hay una sola; en producción haría
 * falta un broker (Kafka, NATS, Redis) detrás de este mismo puerto.
 */
public final class InProcessEventBroadcaster implements EventStream {

    /** Tamaño de cola por defecto de cada suscriptor. */
    public static final int DEFAULT_CAPACITY = 256;

    private static final Logger LOG = Logger.getLogger(InProcessEventBroadcaster.class.getName());

    private final Set<Subscription> subscriptions = new CopyOnWriteArraySet<>();

    @Override
    public void deliver(OutboxMessage message) {
        Objects.requireNonNull(message, "el mensaje no puede ser nulo");
        for (Subscription subscription : subscriptions) {
            subscription.offer(message);
        }
    }

    /** Suscribe un consumidor con la cola por defecto ({@value #DEFAULT_CAPACITY}). */
    public Subscription subscribe(Consumer<OutboxMessage> consumer) {
        return subscribe(consumer, DEFAULT_CAPACITY);
    }

    /**
     * Suscribe un consumidor. Recibirá, en orden y desde su propio hilo, los mensajes que se
     * entreguen a partir de ahora (no los anteriores).
     *
     * @param capacity cuántos mensajes puede tener pendientes antes de empezar a descartar; al menos 1
     */
    public Subscription subscribe(Consumer<OutboxMessage> consumer, int capacity) {
        Objects.requireNonNull(consumer, "el consumidor no puede ser nulo");
        if (capacity < 1) {
            throw new IllegalArgumentException("La cola debe admitir al menos 1 mensaje: " + capacity);
        }
        Subscription subscription = new Subscription(consumer, capacity);
        subscriptions.add(subscription);
        subscription.start();
        return subscription;
    }

    /** Cuántos suscriptores hay ahora mismo. */
    public int subscriberCount() {
        return subscriptions.size();
    }

    /** Una suscripción viva. Se cierra con {@link #close()}; cerrarla dos veces no hace nada. */
    public final class Subscription implements AutoCloseable {

        private final Consumer<OutboxMessage> consumer;
        private final int capacity;
        private final Deque<OutboxMessage> queue = new ArrayDeque<>();
        private final AtomicLong dropped = new AtomicLong();
        private volatile boolean closed;

        private Subscription(Consumer<OutboxMessage> consumer, int capacity) {
            this.consumer = consumer;
            this.capacity = capacity;
        }

        private void start() {
            Thread.ofVirtual().name("payaf-broadcast-", 0).start(this::drain);
        }

        private synchronized void offer(OutboxMessage message) {
            if (closed) {
                return;
            }
            if (queue.size() == capacity) {
                queue.removeFirst();
                dropped.incrementAndGet();
            }
            queue.addLast(message);
            notifyAll();
        }

        private synchronized OutboxMessage next() throws InterruptedException {
            while (queue.isEmpty() && !closed) {
                wait();
            }
            return closed ? null : queue.removeFirst();
        }

        private void drain() {
            try {
                OutboxMessage message;
                while ((message = next()) != null) {
                    consumer.accept(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Se cierra una suscripción cuyo consumidor ha fallado", e);
            } finally {
                close();
            }
        }

        /** Mensajes descartados porque su cola estaba llena. */
        public long dropped() {
            return dropped.get();
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                queue.clear();
                notifyAll();
            }
            subscriptions.remove(this);
        }
    }
}
