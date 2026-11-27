package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger.Rejection;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger.RejectedException;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.SimulatedLedger.State;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulatedLedgerTest {

    private final SimulatedLedger ledger = new SimulatedLedger();

    private static void assertRejected(Runnable operation, Rejection expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(RejectedException.class,
                e -> assertThat(e.rejection()).isEqualTo(expected));
    }

    @Test
    @DisplayName("autorizar, capturar y reembolsar en dos veces hasta el total")
    void happyPath() {
        ledger.open("r1", 1000, "EUR");
        assertThat(ledger.capture("r1", 1000, "EUR").state()).isEqualTo(State.CAPTURED);
        assertThat(ledger.refund("r1", 400, "EUR").refundedMinor()).isEqualTo(400);
        SimulatedLedger.Entry entry = ledger.refund("r1", 600, "EUR");
        assertThat(entry.refundedMinor()).isEqualTo(1000);
        assertThat(entry.refundCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("rechaza referencias desconocidas, dobles capturas, importes que no cuadran y reembolsos de más")
    void rejections() {
        ledger.open("r1", 1000, "EUR");

        assertRejected(() -> ledger.capture("nope", 1000, "EUR"), Rejection.UNKNOWN_REFERENCE);
        assertRejected(() -> ledger.capture("r1", 999, "EUR"), Rejection.AMOUNT_MISMATCH);
        assertRejected(() -> ledger.capture("r1", 1000, "USD"), Rejection.AMOUNT_MISMATCH);
        assertRejected(() -> ledger.refund("r1", 100, "EUR"), Rejection.INVALID_STATE);

        ledger.capture("r1", 1000, "EUR");
        assertRejected(() -> ledger.capture("r1", 1000, "EUR"), Rejection.INVALID_STATE);
        assertRejected(() -> ledger.cancel("r1"), Rejection.INVALID_STATE);
        assertRejected(() -> ledger.refund("r1", 1001, "EUR"), Rejection.REFUND_EXCEEDS_CAPTURED);
        assertRejected(() -> ledger.refund("r1", 0, "EUR"), Rejection.AMOUNT_MISMATCH);
        // Un rechazo no cambia nada.
        assertThat(ledger.find("r1").orElseThrow().refundedMinor()).isZero();
    }

    @Test
    @DisplayName("anular deja la autorización CANCELED y ya no se puede capturar")
    void cancel() {
        ledger.open("r1", 1000, "EUR");
        assertThat(ledger.cancel("r1").state()).isEqualTo(State.CANCELED);
        assertRejected(() -> ledger.capture("r1", 1000, "EUR"), Rejection.INVALID_STATE);
        assertRejected(() -> ledger.cancel("r1"), Rejection.INVALID_STATE);
    }

    @Test
    @DisplayName("una referencia repetida es un error de programación de la PSP")
    void duplicateReference() {
        ledger.open("r1", 1000, "EUR");
        assertThatThrownBy(() -> ledger.open("r1", 1000, "EUR")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("muchas capturas simultáneas de la misma autorización: solo una sale bien")
    void concurrentCaptures() throws Exception {
        ledger.open("r1", 1000, "EUR");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> capture = () -> {
                    start.await();
                    try {
                        ledger.capture("r1", 1000, "EUR");
                        return true;
                    } catch (RejectedException e) {
                        return false;
                    }
                };
                results.add(pool.submit(capture));
            }
            start.countDown();
            int ok = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("reembolsos simultáneos nunca superan lo capturado")
    void concurrentRefunds() throws Exception {
        ledger.open("r1", 1000, "EUR");
        ledger.capture("r1", 1000, "EUR");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        ledger.refund("r1", 100, "EUR");
                    } catch (RejectedException ignored) {
                        // Se acaba el saldo: es lo esperado a partir del décimo.
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        SimulatedLedger.Entry entry = ledger.find("r1").orElseThrow();
        assertThat(entry.refundedMinor()).isEqualTo(1000);
        assertThat(entry.refundCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("la regla de rechazo determinista: los importes terminados en 51")
    void deterministicDecline() {
        assertThat(DeterministicDecline.declines(10051)).isTrue();
        assertThat(DeterministicDecline.declines(51)).isTrue();
        assertThat(DeterministicDecline.declines(151)).isTrue();
        assertThat(DeterministicDecline.declines(10050)).isFalse();
        assertThat(DeterministicDecline.declines(5100)).isFalse();
        assertThat(DeterministicDecline.declines(10052)).isFalse();
    }
}
