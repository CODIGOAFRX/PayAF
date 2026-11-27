package dev.pedrogomez.payaf.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

    private final MutableTestClock clock = new MutableTestClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    @DisplayName("admite hasta el límite por clave y dice cuánto falta para la siguiente")
    void limitsPerKey() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();
            clock.advance(Duration.ofSeconds(10));
        }
        SlidingWindowRateLimiter.Decision denied = limiter.tryAcquire("1.1.1.1");
        assertThat(denied.allowed()).isFalse();
        // La primera petición fue hace 30 s: faltan 30 s para que salga de la ventana.
        assertThat(denied.retryAfterSeconds()).isEqualTo(30);
        assertThat(limiter.tryAcquire("2.2.2.2").allowed()).isTrue();
    }

    @Test
    @DisplayName("la ventana desliza: al pasar un minuto desde la más antigua vuelve a admitir")
    void windowSlides() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k");
        }
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
        clock.advance(Duration.ofSeconds(60));
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
    }

    @Test
    @DisplayName("olvida las claves inactivas para no crecer sin fin")
    void sweepsIdleKeys() {
        for (int i = 0; i < 300; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        clock.advance(Duration.ofMinutes(2));
        for (int i = 0; i < 300; i++) {
            limiter.tryAcquire("nueva");
        }
        assertThat(limiter.trackedKeys()).isLessThan(300);
    }
}
