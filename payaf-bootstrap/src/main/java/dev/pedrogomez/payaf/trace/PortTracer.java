package dev.pedrogomez.payaf.trace;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.shared.DomainException;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Envuelve puertos en decoradores que cuentan qué se ha tocado en cada operación: el evento
 * {@code port.touched} que el panel usa para iluminar el hexágono.
 * <p>
 * <strong>Esto es observabilidad, y se cablea en el borde.</strong> Ni el dominio ni la aplicación
 * saben que existe: los casos de uso reciben sus puertos por constructor y {@code ApplicationConfig}
 * les pasa estos envoltorios en lugar de los adaptadores desnudos. Es el punto didáctico del
 * hexágono: como el núcleo solo depende de interfaces, se le puede añadir un comportamiento
 * transversal (trazas, métricas, reintentos) sin cambiar una línea suya, igual que se cambia
 * memoria por PostgreSQL. Los decoradores son proxies dinámicos del JDK: el contrato de cada puerto
 * pasa intacto, con los mismos valores devueltos y las mismas excepciones.
 * <p>
 * Dos tipos de decorador:
 * <ul>
 *   <li>{@link #driving}: la entrada de un adaptador driving (REST, webhook, CLI) a un puerto de
 *       entrada. Si el hilo tiene un {@link TraceContext} con canal, abre la traza, numera los
 *       puertos que se toquen dentro y, al terminar, emite el suyo, que siempre es el último: así el
 *       panel sabe que la traza está completa.</li>
 *   <li>{@link #driven}: un puerto de salida (pasarela, repositorio, almacenes, publicador de
 *       eventos, transacciones). Solo emite si hay una traza abierta; fuera de una operación (el
 *       relay del outbox, el reaper, una consulta del panel) no hace nada.</li>
 * </ul>
 * Cada evento lleva el orden de inicio ({@code seq}) y lo que tardó ({@code ms}). Se emite al
 * terminar la llamada, así que un puerto que envuelve a otros (las transacciones) llega después de
 * los que contiene, pero con un {@code seq} menor.
 */
public final class PortTracer {

    /** Lado del hexágono. */
    public enum Side {
        DRIVING, DRIVEN
    }

    /**
     * Un puerto tocado.
     *
     * @param port      la interfaz del puerto ({@code PaymentGateway}, {@code AuthorizePaymentUseCase}...)
     * @param adapter   el adaptador que hay detrás o delante ({@code rest}, {@code redsys}, {@code memory}...)
     * @param impl      la clase concreta, para el registro
     * @param operation el método llamado
     * @param outcome   cómo acabó ({@code OK}, {@code ERROR}, {@code AlreadyCompleted}, un código de error...)
     * @param code      el código nativo de la PSP, si lo hay
     * @param ms        cuánto tardó, en milisegundos
     * @param seq       orden de inicio dentro de la operación (0 es el puerto driving)
     */
    public record PortTouch(String correlationId, Side side, String port, String adapter, String impl,
                            String operation, String outcome, String code, long ms, int seq) {

        public PortTouch {
            Objects.requireNonNull(correlationId);
            Objects.requireNonNull(side);
            Objects.requireNonNull(port);
            Objects.requireNonNull(adapter);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(outcome);
        }
    }

    private final Consumer<PortTouch> sink;

    /** @param sink adónde van los eventos; no debe bloquear ni lanzar excepciones */
    public PortTracer(Consumer<PortTouch> sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    /**
     * Decora la entrada a un puerto de entrada. El proxy implementa {@code port} (que puede extender
     * varias interfaces de casos de uso) y el nombre del puerto que se emite es el de la interfaz
     * que declara el método llamado.
     */
    public <T> T driving(Class<T> port, T target) {
        Objects.requireNonNull(target);
        return proxy(port, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invoke(target, method, args);
            }
            Optional<TraceContext.State> context = TraceContext.current();
            if (context.isEmpty() || context.get().channel().isEmpty() || context.get().tracing()) {
                return invoke(target, method, args);
            }
            TraceContext.State state = context.get();
            state.tracing(true);
            int seq = state.nextSeq();
            long start = System.nanoTime();
            String outcome = "OK";
            try {
                Object result = invoke(target, method, args);
                outcome = describe(result);
                return result;
            } catch (Throwable e) {
                outcome = describeFailure(e);
                throw e;
            } finally {
                state.tracing(false);
                emit(new PortTouch(state.correlationId(), Side.DRIVING, method.getDeclaringClass().getSimpleName(),
                        state.channel().orElseThrow(), target.getClass().getSimpleName(), method.getName(), outcome,
                        null, elapsedMs(start), seq));
            }
        });
    }

    /** Decora un puerto de salida con el nombre del adaptador que lo implementa. */
    public <T> T driven(Class<T> port, T target, String adapter) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(adapter);
        return proxy(port, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class || isIdentity(method)) {
                return invoke(target, method, args);
            }
            Optional<TraceContext.State> context = TraceContext.current().filter(TraceContext.State::tracing);
            if (context.isEmpty()) {
                return invoke(target, method, args);
            }
            TraceContext.State state = context.get();
            int seq = state.nextSeq();
            long start = System.nanoTime();
            String outcome = "OK";
            String code = null;
            try {
                Object result = invoke(target, method, args);
                outcome = describe(result);
                if (result instanceof GatewayResponse response) {
                    code = response.code().orElse(null);
                }
                return result;
            } catch (Throwable e) {
                outcome = describeFailure(e);
                throw e;
            } finally {
                emit(new PortTouch(state.correlationId(), Side.DRIVEN, port.getSimpleName(), adapter,
                        target.getClass().getSimpleName(), method.getName(), outcome, code, elapsedMs(start), seq));
            }
        });
    }

    /**
     * Decora el catálogo: los perfiles pasan tal cual (son datos, no una llamada a nadie) y cada
     * {@link PaymentGateway} que entrega sale envuelto, con su identificador como adaptador.
     */
    public GatewayCatalog catalog(GatewayCatalog target) {
        Objects.requireNonNull(target);
        return new GatewayCatalog() {
            @Override
            public List<GatewayProfile> profiles() {
                return target.profiles();
            }

            @Override
            public Optional<PaymentGateway> gateway(GatewayId id) {
                return target.gateway(id).map(g -> driven(PaymentGateway.class, g, id.value()));
            }
        };
    }

    /**
     * El adaptador de persistencia, a partir del paquete de la clase que implementa el puerto:
     * {@code memory} o {@code postgres}. Así la traza dice la verdad sobre el perfil activo sin
     * que nadie se lo cuente.
     */
    public static String persistenceAdapter(Object target) {
        String pkg = target.getClass().getPackageName();
        if (pkg.endsWith(".persistencememory")) {
            return "memory";
        }
        if (pkg.endsWith(".persistencejpa")) {
            return "postgres";
        }
        return target.getClass().getSimpleName();
    }

    private void emit(PortTouch touch) {
        try {
            sink.accept(touch);
        } catch (RuntimeException ignored) {
            // La traza nunca puede romper una operación.
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> port, InvocationHandler handler) {
        if (!port.isInterface()) {
            throw new IllegalArgumentException(port + " no es una interfaz");
        }
        return (T) Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** {@code PaymentGateway.id()} es identidad, no una llamada a la PSP. */
    private static boolean isIdentity(Method method) {
        return method.getParameterCount() == 0 && method.getName().equals("id");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    static String describe(Object result) {
        return switch (result) {
            case null -> "OK";
            case GatewayResponse r -> r.outcome().name();
            case IdempotencyStore.Reservation r -> r.getClass().getSimpleName();
            case Optional<?> o -> o.isPresent() ? "FOUND" : "EMPTY";
            case AuthorizationResult r -> r.replayed() ? "REPLAYED" : r.payment().status().name();
            case PaymentView p -> p.status().name();
            case Enum<?> e -> e.name();
            default -> "OK";
        };
    }

    static String describeFailure(Throwable e) {
        return switch (e) {
            case DomainException d -> d.code();
            case ApplicationException a -> a.code();
            default -> e.getClass().getSimpleName();
        };
    }
}
