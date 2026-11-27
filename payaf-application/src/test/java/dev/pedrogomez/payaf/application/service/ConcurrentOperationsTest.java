package dev.pedrogomez.payaf.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.fake.FunctionalGateway;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.domain.event.PaymentCaptured;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Concurrencia optimista en las operaciones sobre un pago ya autorizado: dos peticiones que
 * cargan el mismo pago y lo guardan después no pueden pisarse.
 */
class ConcurrentOperationsTest {

    @RepeatedTest(20)
    @DisplayName("dos capturas a la vez aprobadas por la PSP: las dos devuelven CAPTURED y solo se aplica una")
    void twoConcurrentCaptures() throws Exception {
        Harness h = new Harness(CircuitBreakerConfig.of(Harness.FAILURE_THRESHOLD, Harness.OPEN_DURATION), false);
        // Las dos capturas esperan en la PSP a que la otra también haya llegado: así las dos
        // han cargado el pago (con la misma versión) antes de que ninguna lo guarde.
        CyclicBarrier bothAtGateway = new CyclicBarrier(2);
        FunctionalGateway stripe = new FunctionalGateway(Harness.STRIPE, h.transactions,
                auth -> GatewayResponse.approved("ch_1", Duration.ofMillis(10)))
                .onOperation(op -> {
                    try {
                        bothAtGateway.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return GatewayResponse.approved("ch_1", Duration.ofMillis(10));
                });
        h.catalog.register(Harness.profile(Harness.STRIPE, 140), stripe);
        PaymentId id = h.authorize("k1").payment().id();

        List<Object> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        return h.operations.capture(id);
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            for (Future<Object> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // La PSP de este test aprueba las dos llamadas: la segunda choca al guardar, recarga, ve
        // el pago ya capturado y lo devuelve sin error (un APPROVED nunca acaba en CME).
        assertThat(results).allSatisfy(r -> assertThat(r)
                .isInstanceOfSatisfying(PaymentView.class,
                        v -> assertThat(v.status()).isEqualTo(PaymentStatus.CAPTURED)));

        assertThat(h.payments.stored(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(h.events.published(PaymentCaptured.class)).hasSize(1);
        h.assertTransactionDiscipline();
    }
}
