package dev.pedrogomez.payaf.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReaperThresholdTest {

    @Test
    @DisplayName("la configuración real (2 min frente a 4 pasarelas × 2 s) cumple el margen de ×4")
    void defaultsAreSafe() {
        assertThatCode(() -> ReaperScheduler.requireSafeThreshold(ReaperScheduler.OLDER_THAN, 4,
                Duration.ofSeconds(2))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("justo en el límite vale; por debajo, falla al arrancar con un mensaje claro")
    void boundary() {
        assertThatCode(() -> ReaperScheduler.requireSafeThreshold(Duration.ofSeconds(32), 4, Duration.ofSeconds(2)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ReaperScheduler.requireSafeThreshold(Duration.ofSeconds(31), 4,
                Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("umbral del reaper")
                .hasMessageContaining("4 pasarelas");
    }
}
