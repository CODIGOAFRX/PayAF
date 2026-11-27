package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Inyector de averías de una pasarela simulada. Hay uno por pasarela y es seguro para hilos:
 * la configuración se cambia de forma atómica (desde el panel, vía
 * {@link GatewayChaosControl}) mientras otras peticiones están llamando.
 * <p>
 * Cada llamada a la PSP pasa por {@link #call}, que aplica la configuración vigente en este
 * orden:
 * <ol>
 *   <li><strong>Latencia y timeout.</strong> Si la latencia configurada supera el
 *       {@linkplain #timeout() timeout} de la pasarela (2 s por defecto), se espera solo hasta
 *       el timeout y se devuelve TIMEOUT: es lo que vería un cliente HTTP que deja de esperar.
 *       Si no, se espera la latencia.</li>
 *   <li><strong>Caída</strong> ({@code down}): ERROR con código {@value #HTTP_503}.</li>
 *   <li><strong>Error aleatorio</strong> ({@code errorRate}): ERROR con código
 *       {@value #HTTP_500} con esa probabilidad, según el {@link Random} inyectado.</li>
 *   <li>Si no hay avería, se llama de verdad a la PSP simulada.</li>
 * </ol>
 * En los casos 1 a 3 la PSP no llega a enterarse de la petición: no se apunta nada en su
 * libro. (Una PSP real sí podría haber procesado una petición cuya respuesta no llegó a
 * tiempo; ese caso, el de los pagos huérfanos, lo cubre el reaper de la aplicación.) La
 * latencia de la {@link GatewayResponse} es siempre el tiempo simulado (la latencia
 * configurada, o el timeout si se ha agotado), no lo que haya tardado el código.
 * <p>
 * Las esperas pasan por un {@link Sleeper} inyectable, para que los tests no duerman.
 */
public final class FaultInjector {

    /** Código del ERROR que devuelve una PSP caída. */
    public static final String HTTP_503 = "HTTP_503";
    /** Código del ERROR aleatorio de {@code errorRate}. */
    public static final String HTTP_500 = "HTTP_500";
    /** Código del ERROR si la propia PSP simulada falla de forma inesperada (un fallo nuestro). */
    public static final String SIMULATOR_FAILURE = "SIMULATOR_FAILURE";
    /** Timeout de una pasarela si no se indica otro. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private final AtomicReference<FaultConfig> config = new AtomicReference<>(FaultConfig.none());
    private final Duration timeout;
    private final Random random;
    private final Sleeper sleeper;

    /** Con el timeout por defecto ({@link #DEFAULT_TIMEOUT}). */
    public FaultInjector(Random random, Sleeper sleeper) {
        this(DEFAULT_TIMEOUT, random, sleeper);
    }

    public FaultInjector(Duration timeout, Random random, Sleeper sleeper) {
        Objects.requireNonNull(timeout, "el timeout no puede ser nulo");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("El timeout debe ser positivo: " + timeout);
        }
        this.timeout = timeout;
        this.random = Objects.requireNonNull(random, "el generador aleatorio no puede ser nulo");
        this.sleeper = Objects.requireNonNull(sleeper, "el sleeper no puede ser nulo");
    }

    public FaultConfig config() {
        return config.get();
    }

    public void configure(FaultConfig newConfig) {
        config.set(Objects.requireNonNull(newConfig, "la configuración no puede ser nula"));
    }

    /** Quita todas las averías. */
    public void reset() {
        config.set(FaultConfig.none());
    }

    /** Cuánto espera el cliente a la PSP antes de rendirse. */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Ejecuta {@code pspCall} con las averías vigentes. {@code pspCall} es la llamada a la PSP
     * ya traducida; su latencia se sustituye por la simulada. No lanza nunca: si
     * {@code pspCall} lanzara o devolviera {@code null}, se devuelve un ERROR
     * {@value #SIMULATOR_FAILURE}.
     */
    public GatewayResponse call(Supplier<GatewayResponse> pspCall) {
        FaultConfig current = config.get();
        Duration latency = current.latency();
        if (latency.compareTo(timeout) > 0) {
            sleeper.sleep(timeout);
            return GatewayResponse.timeout(timeout);
        }
        sleeper.sleep(latency);
        if (current.down()) {
            return GatewayResponse.error(HTTP_503, latency);
        }
        if (current.errorRate() > 0.0 && nextDouble() < current.errorRate()) {
            return GatewayResponse.error(HTTP_500, latency);
        }
        GatewayResponse response;
        try {
            response = pspCall.get();
        } catch (RuntimeException e) {
            return GatewayResponse.error(SIMULATOR_FAILURE, latency);
        }
        if (response == null) {
            return GatewayResponse.error(SIMULATOR_FAILURE, latency);
        }
        return new GatewayResponse(response.outcome(), response.gatewayReference(), response.code(), latency);
    }

    /** El {@link Random} inyectado puede no ser seguro para hilos (un fake de test). */
    private double nextDouble() {
        synchronized (random) {
            return random.nextDouble();
        }
    }
}
