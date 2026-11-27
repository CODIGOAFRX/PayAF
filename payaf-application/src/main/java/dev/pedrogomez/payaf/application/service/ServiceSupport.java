package dev.pedrogomez.payaf.application.service;

import dev.pedrogomez.payaf.application.port.out.CircuitBreakerStore;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.DomainEvent;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.routing.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Piezas comunes de los servicios: el acceso serializado a los breakers compartidos, la
 * llamada defensiva a una pasarela y el instante monótono para el agregado.
 * <p>
 * Es interna al paquete: no es un puerto ni un caso de uso, solo evita repetir código.
 */
final class ServiceSupport {

    /** Código del intento cuando el adaptador incumple su contrato y lanza una excepción. */
    static final String ADAPTER_EXCEPTION = "ADAPTER_EXCEPTION";

    private ServiceSupport() {
    }

    // ---------------------------------------------------------------------------------
    // Breakers compartidos
    // ---------------------------------------------------------------------------------
    //
    // CircuitBreaker no es seguro para hilos y su estado lo comparten todas las peticiones
    // (ver CircuitBreakerStore). Cada cambio se hace sincronizado sobre la instancia; con el
    // almacén en memoria, que devuelve siempre la misma, eso serializa el acceso de verdad.
    // Los cerrojos solo envuelven operaciones en memoria, nunca una llamada a una pasarela.

    static boolean tryAcquire(CircuitBreaker breaker, Instant now) {
        synchronized (breaker) {
            return breaker.tryAcquirePermission(now);
        }
    }

    static void recordAndSave(CircuitBreakerStore store, CircuitBreaker breaker, AttemptOutcome outcome,
                              Instant now) {
        synchronized (breaker) {
            breaker.record(outcome, now);
            store.save(breaker);
        }
    }

    static List<DomainEvent> pullEvents(Collection<CircuitBreaker> breakers) {
        List<DomainEvent> events = new ArrayList<>();
        for (CircuitBreaker breaker : breakers) {
            synchronized (breaker) {
                events.addAll(breaker.pullEvents());
            }
        }
        return events;
    }

    /**
     * Ejecuta {@code work} con todos los breakers bloqueados, tomando los cerrojos siempre en
     * el mismo orden (por pasarela) para que dos peticiones no se bloqueen mutuamente. Lo
     * necesita la política de enrutado, que consulta (y puede hacer avanzar) todos a la vez.
     */
    static <T> T withAllLocked(Collection<CircuitBreaker> breakers, Supplier<T> work) {
        List<CircuitBreaker> ordered = new ArrayList<>(breakers);
        ordered.sort(Comparator.comparing(CircuitBreaker::gateway));
        return lockFrom(ordered, 0, work);
    }

    private static <T> T lockFrom(List<CircuitBreaker> ordered, int index, Supplier<T> work) {
        if (index == ordered.size()) {
            return work.get();
        }
        synchronized (ordered.get(index)) {
            return lockFrom(ordered, index + 1, work);
        }
    }

    // ---------------------------------------------------------------------------------
    // Llamadas a pasarelas
    // ---------------------------------------------------------------------------------

    /**
     * Llama a la pasarela. El contrato de {@code PaymentGateway} dice que el adaptador nunca
     * lanza excepciones por fallos técnicos; si aun así lanza una (o devuelve {@code null}),
     * se trata como ERROR en lugar de dejar el pago a medias: así cuenta para el breaker y el
     * enrutado sigue con la siguiente pasarela, igual que con un TIMEOUT.
     * <p>
     * Se atrapa {@link Exception} y no solo {@link RuntimeException}: un adaptador puede
     * lanzar una excepción comprobada (una {@code IOException}) sin declararla, de forma
     * «sneaky», y el compilador no lo ve. Los {@link Error} (memoria agotada, una clase que
     * falta) NO se atrapan: son fallos de la JVM, no de la pasarela, y disfrazarlos de ERROR
     * de una PSP escondería el problema real.
     */
    static GatewayResponse callSafely(Clock clock, Supplier<GatewayResponse> call) {
        Instant start = clock.instant();
        try {
            GatewayResponse response = call.get();
            if (response != null) {
                return response;
            }
        } catch (Exception e) {
            // Se traduce abajo: el adaptador ha incumplido el contrato. Si era una interrupción
            // (aunque llegue lanzada a escondidas), se restaura el flag del hilo para que quien
            // gestiona el hilo (un apagado ordenado, por ejemplo) se entere.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        Duration latency = Duration.between(start, clock.instant());
        return GatewayResponse.error(ADAPTER_EXCEPTION, latency.isNegative() ? Duration.ZERO : latency);
    }

    // ---------------------------------------------------------------------------------
    // Tiempo
    // ---------------------------------------------------------------------------------

    /**
     * Instante fresco del reloj para el siguiente cambio del pago, sin bajar nunca de su
     * última actualización. El agregado rechaza instantes anteriores
     * ({@code NonMonotonicTimeException}); si el reloj del sistema retrocede un poco (un
     * ajuste de NTP) se usa el último instante del pago en lugar de romper la operación.
     */
    static Instant nextInstant(Clock clock, Payment payment) {
        Instant now = clock.instant();
        return now.isBefore(payment.updatedAt()) ? payment.updatedAt() : now;
    }
}
