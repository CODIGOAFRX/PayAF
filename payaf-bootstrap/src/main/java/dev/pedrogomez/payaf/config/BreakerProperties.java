package dev.pedrogomez.payaf.config;

import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del circuit breaker en {@code application.yml} ({@code payaf.breaker.*}). Se
 * traducen a la {@link CircuitBreakerConfig} del dominio, que es quien valida los límites.
 *
 * @param failureThreshold fallos técnicos seguidos que abren el circuito
 * @param openDuration     cuánto se queda abierto antes de volver a probar
 */
@ConfigurationProperties(prefix = "payaf.breaker")
public record BreakerProperties(int failureThreshold, Duration openDuration) {

    public CircuitBreakerConfig toDomain() {
        return CircuitBreakerConfig.of(failureThreshold, openDuration);
    }
}
