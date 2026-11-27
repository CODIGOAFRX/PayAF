package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.RecordingSleeper;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimFixtures.ScriptedRandom;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaultInjectorTest {

    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final AtomicInteger pspCalls = new AtomicInteger();
    private final Supplier<GatewayResponse> psp = () -> {
        pspCalls.incrementAndGet();
        return GatewayResponse.approved("ref-1", Duration.ofSeconds(99));
    };

    private FaultInjector injector(Random random) {
        return new FaultInjector(random, sleeper);
    }

    @Test
    @DisplayName("sin averías llama a la PSP, no duerme y la latencia es la simulada (cero)")
    void noFaults() {
        GatewayResponse response = injector(new ScriptedRandom()).call(psp);

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(response.gatewayReference()).contains("ref-1");
        assertThat(response.latency()).isZero();
        assertThat(pspCalls).hasValue(1);
        assertThat(sleeper.total()).isZero();
    }

    @Test
    @DisplayName("la latencia se duerme con el Sleeper y es la latencia de la respuesta")
    void latency() {
        FaultInjector faults = injector(new ScriptedRandom());
        faults.configure(FaultConfig.none().withLatency(Duration.ofMillis(300)));

        GatewayResponse response = faults.call(psp);

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(response.latency()).isEqualTo(Duration.ofMillis(300));
        assertThat(sleeper.sleeps()).containsExactly(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("una latencia mayor que el timeout duerme solo hasta el timeout y da TIMEOUT sin llamar a la PSP")
    void latencyBeyondTimeout() {
        FaultInjector faults = injector(new ScriptedRandom());
        faults.configure(FaultConfig.none().withLatency(Duration.ofSeconds(5)));

        GatewayResponse response = faults.call(psp);

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
        assertThat(response.latency()).isEqualTo(FaultInjector.DEFAULT_TIMEOUT);
        assertThat(sleeper.sleeps()).containsExactly(Duration.ofSeconds(2));
        assertThat(pspCalls).hasValue(0);
    }

    @Test
    @DisplayName("una latencia igual al timeout aún llega a tiempo")
    void latencyEqualToTimeout() {
        FaultInjector faults = new FaultInjector(Duration.ofMillis(500), new ScriptedRandom(), sleeper);
        faults.configure(FaultConfig.none().withLatency(Duration.ofMillis(500)));

        assertThat(faults.call(psp).outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(faults.timeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("caída: ERROR HTTP_503 sin llamar a la PSP, con la latencia configurada")
    void down() {
        FaultInjector faults = injector(new ScriptedRandom());
        faults.configure(FaultConfig.none().withDown(true).withLatency(Duration.ofMillis(40)));

        GatewayResponse response = faults.call(psp);

        assertThat(response.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(response.code()).contains(FaultInjector.HTTP_503);
        assertThat(response.latency()).isEqualTo(Duration.ofMillis(40));
        assertThat(pspCalls).hasValue(0);
    }

    @Test
    @DisplayName("caída con latencia por encima del timeout: gana el TIMEOUT")
    void downAndSlow() {
        FaultInjector faults = injector(new ScriptedRandom());
        faults.configure(new FaultConfig(true, Duration.ofSeconds(3), 0));

        assertThat(faults.call(psp).outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
    }

    @Test
    @DisplayName("tasa de error: falla con HTTP_500 cuando el aleatorio queda por debajo")
    void errorRate() {
        FaultInjector faults = injector(new ScriptedRandom(0.29, 0.30, 0.95));
        faults.configure(FaultConfig.none().withErrorRate(0.3));

        GatewayResponse first = faults.call(psp);
        GatewayResponse second = faults.call(psp);
        GatewayResponse third = faults.call(psp);

        assertThat(first.outcome()).isEqualTo(AttemptOutcome.ERROR);
        assertThat(first.code()).contains(FaultInjector.HTTP_500);
        assertThat(second.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(third.outcome()).isEqualTo(AttemptOutcome.APPROVED);
        assertThat(pspCalls).hasValue(2);
    }

    @Test
    @DisplayName("con tasa 0 no se consulta el aleatorio; con tasa 1 falla siempre")
    void errorRateBounds() {
        FaultInjector never = injector(new ScriptedRandom());
        assertThat(never.call(psp).outcome()).isEqualTo(AttemptOutcome.APPROVED);

        FaultInjector always = injector(new Random(7));
        always.configure(FaultConfig.none().withErrorRate(1.0));
        for (int i = 0; i < 50; i++) {
            assertThat(always.call(psp).outcome()).isEqualTo(AttemptOutcome.ERROR);
        }
    }

    @Test
    @DisplayName("si la PSP simulada lanza o devuelve null, ERROR SIMULATOR_FAILURE: el adaptador nunca lanza")
    void neverThrows() {
        FaultInjector faults = injector(new ScriptedRandom());

        assertThat(faults.call(() -> {
            throw new IllegalStateException("boom");
        }).code()).contains(FaultInjector.SIMULATOR_FAILURE);
        assertThat(faults.call(() -> null).code()).contains(FaultInjector.SIMULATOR_FAILURE);
    }

    @Test
    @DisplayName("reset quita las averías")
    void reset() {
        FaultInjector faults = injector(new ScriptedRandom());
        faults.configure(FaultConfig.none().withDown(true));
        faults.reset();

        assertThat(faults.config()).isEqualTo(FaultConfig.none());
        assertThat(faults.call(psp).outcome()).isEqualTo(AttemptOutcome.APPROVED);
    }

    @Test
    @DisplayName("valida la configuración y el timeout")
    void validation() {
        assertThatThrownBy(() -> new FaultConfig(false, Duration.ofMillis(-1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaultConfig.none().withErrorRate(1.01)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaultConfig.none().withErrorRate(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaultConfig.none().withErrorRate(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FaultInjector(Duration.ZERO, new Random(), sleeper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> injector(new Random()).configure(null)).isInstanceOf(NullPointerException.class);
    }
}
