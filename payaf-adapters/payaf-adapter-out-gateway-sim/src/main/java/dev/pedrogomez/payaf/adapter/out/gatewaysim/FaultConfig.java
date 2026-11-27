package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import java.time.Duration;
import java.util.Objects;

/**
 * Averías que se inyectan en una pasarela simulada. Los modos se combinan entre sí.
 *
 * @param down      la PSP está caída: responde como un HTTP 503 (ERROR con código
 *                  {@value FaultInjector#HTTP_503}) sin llegar a procesar nada
 * @param latency   latencia añadida a cada llamada; no negativa. Si supera el timeout de la
 *                  pasarela, la llamada acaba en TIMEOUT
 * @param errorRate probabilidad, de 0 a 1, de que una llamada falle como un HTTP 500 (ERROR con
 *                  código {@value FaultInjector#HTTP_500}) sin llegar a procesarse
 */
public record FaultConfig(boolean down, Duration latency, double errorRate) {

    private static final FaultConfig NONE = new FaultConfig(false, Duration.ZERO, 0.0);

    public FaultConfig {
        Objects.requireNonNull(latency, "la latencia no puede ser nula");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("La latencia no puede ser negativa: " + latency);
        }
        if (Double.isNaN(errorRate) || errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException("La tasa de error debe estar entre 0 y 1: " + errorRate);
        }
    }

    /** Sin averías: la PSP funciona perfectamente y al instante. */
    public static FaultConfig none() {
        return NONE;
    }

    public FaultConfig withDown(boolean down) {
        return new FaultConfig(down, latency, errorRate);
    }

    public FaultConfig withLatency(Duration latency) {
        return new FaultConfig(down, latency, errorRate);
    }

    public FaultConfig withErrorRate(double errorRate) {
        return new FaultConfig(down, latency, errorRate);
    }
}
