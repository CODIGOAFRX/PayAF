package dev.pedrogomez.payaf.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.GatewaySimulator;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.Sleeper;
import dev.pedrogomez.payaf.application.port.in.ReapReport;
import dev.pedrogomez.payaf.application.port.in.ReapStalePaymentsUseCase;
import dev.pedrogomez.payaf.application.port.out.GatewayCatalog;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tester (fase 4a): la validación «olderThan ≥ 4 × (nº de pasarelas × timeout)» se hace al
 * ARRANCAR el contexto de Spring, no solo en el método estático. Con 4 pasarelas y el umbral de
 * 2 min, el timeout máximo admisible es 7,5 s (4 × 4 × 7,5 s = 120 s).
 */
class ReaperThresholdStartupTest {

    private static ApplicationContextRunner runnerWithTimeout(Duration timeout) {
        GatewaySimulator simulator = GatewaySimulator.create(new Random(1), duration -> { }, timeout);
        return new ApplicationContextRunner()
                .withUserConfiguration(ReaperScheduler.class)
                .withBean(GatewaySimulator.class, () -> simulator)
                .withBean(GatewayCatalog.class, simulator::catalog)
                .withBean(ReapStalePaymentsUseCase.class, () -> olderThan -> new ReapReport(0, 0));
    }

    @Test
    @DisplayName("con el timeout por defecto (2 s) el contexto arranca y hay ReaperScheduler")
    void defaultTimeoutStarts() {
        runnerWithTimeout(Duration.ofSeconds(2)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ReaperScheduler.class);
        });
    }

    @Test
    @DisplayName("justo en el límite (timeout 7,5 s → 120 s exigidos) arranca")
    void exactlyAtLimitStarts() {
        runnerWithTimeout(Duration.ofMillis(7_500)).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("1 ms por encima (7,501 s de timeout) el contexto NO arranca y el mensaje explica por qué")
    void aboveLimitFailsAtStartup() {
        runnerWithTimeout(Duration.ofMillis(7_501)).run(ctx -> {
            assertThat(ctx).hasFailed();
            Throwable root = ctx.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("umbral del reaper")
                    .hasMessageContaining("4 pasarelas");
        });
    }

    @Test
    @DisplayName("con payaf.reaper.enabled=false no hay planificador y por tanto no se valida: arranca con cualquier timeout")
    void disabledReaperSkipsValidation() {
        runnerWithTimeout(Duration.ofSeconds(30)).withPropertyValues("payaf.reaper.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(ReaperScheduler.class);
                });
    }

    @Test
    @DisplayName("el contexto real cumple la regla: 4 pasarelas del catálogo y 2 s de timeout del simulador")
    void realWiringValues() {
        GatewaySimulator real = GatewaySimulator.create(new Random(1), Sleeper.system());
        assertThat(real.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(real.catalog().profiles()).hasSize(4);
        assertThat(ReaperScheduler.OLDER_THAN)
                .isGreaterThanOrEqualTo(real.timeout().multipliedBy(4).multipliedBy(ReaperScheduler.SAFETY_FACTOR));
    }
}
