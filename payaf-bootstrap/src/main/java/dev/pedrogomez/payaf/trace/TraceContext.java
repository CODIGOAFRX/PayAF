package dev.pedrogomez.payaf.trace;

import java.util.Objects;
import java.util.Optional;

/**
 * El contexto de traza del hilo actual: el {@code correlationId} de la operación, el adaptador
 * driving por el que ha entrado ({@code rest}, {@code webhook} o {@code cli}) y el contador que
 * ordena los puertos que va tocando.
 * <p>
 * Vive en un {@link ThreadLocal} porque los casos de uso son síncronos: todo lo que hace una
 * operación (enrutar, guardar, publicar) ocurre en el hilo que la recibió. Lo abren los bordes:
 * el {@link CorrelationIdFilter} para HTTP y {@code CliConfig} para la terminal. El núcleo no lo
 * ve nunca.
 */
public final class TraceContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * Abre el contexto de una petición en este hilo. Se cierra con el {@link Scope} devuelto, que
     * restaura el que hubiera antes.
     *
     * @param channel el adaptador driving ({@code rest}, {@code webhook}, {@code cli}), o {@code null}
     *                si la petición no entra por ninguno trazable
     */
    public static Scope open(String correlationId, String channel) {
        State previous = CURRENT.get();
        CURRENT.set(new State(Objects.requireNonNull(correlationId), channel));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** El contexto de este hilo, si hay uno abierto. */
    public static Optional<State> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Cierra un contexto abierto con {@link #open}. No lanza excepciones. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    /** Estado mutable de la traza de una petición; solo lo toca el hilo que la atiende. */
    public static final class State {

        private final String correlationId;
        private final String channel;
        private int nextSeq;
        private boolean tracing;

        private State(String correlationId, String channel) {
            this.correlationId = correlationId;
            this.channel = channel;
        }

        public String correlationId() {
            return correlationId;
        }

        public Optional<String> channel() {
            return Optional.ofNullable(channel);
        }

        /** Si un puerto driving ya ha abierto la traza de esta operación. */
        boolean tracing() {
            return tracing;
        }

        void tracing(boolean value) {
            tracing = value;
        }

        /** Orden de inicio de cada puerto tocado; el puerto driving es el 0. */
        int nextSeq() {
            return nextSeq++;
        }
    }
}
