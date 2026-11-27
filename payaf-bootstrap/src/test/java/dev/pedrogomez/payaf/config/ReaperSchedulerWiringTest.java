package dev.pedrogomez.payaf.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.application.port.out.IdempotencyStore;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.application.port.out.TransactionRunner;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentRequest;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tester (fase 4a): con la configuración por defecto el reaper programado está activo, pero no
 * puede interferir con los tests: no arranca hasta pasados 30 s y solo toca PENDING de más de
 * 2 min (con el reloj real del contexto).
 */
@SpringBootTest
@ActiveProfiles("memory")
class ReaperSchedulerWiringTest {

    @Autowired
    private Environment environment;
    @Autowired
    private ReaperScheduler scheduler;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private IdempotencyStore idempotency;
    @Autowired
    private TransactionRunner transactions;
    @Autowired
    private Clock clock;

    @Test
    @DisplayName("payaf.reaper.enabled=true en application.yml y el ReaperScheduler existe")
    void enabledByDefault() {
        assertThat(environment.getProperty("payaf.reaper.enabled", Boolean.class)).isTrue();
        assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("programación: cada 30 s, primera pasada a los 30 s y antigüedad mínima de 2 min")
    void schedule() throws NoSuchMethodException {
        Method reap = ReaperScheduler.class.getMethod("reap");
        Scheduled scheduled = reap.getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelay()).isEqualTo(30);
        assertThat(scheduled.initialDelay()).isEqualTo(30);
        assertThat(scheduled.timeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(ReaperScheduler.OLDER_THAN).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("una pasada manual no toca un PENDING recién creado (reloj real) ni lanza")
    void manualPassLeavesFreshPendingAlone() {
        String key = "fresh-" + UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(Money.of(10_000, Currency.EUR), CountryCode.of("ES"), "p",
                IdempotencyKey.of(key));
        PaymentId id = PaymentId.of(UUID.randomUUID());
        transactions.inTransaction(() -> {
            idempotency.tryReserve(request.key(), request.fingerprint(), id, clock.instant());
            payments.save(Payment.create(id, request, clock.instant()));
            return null;
        });

        scheduler.reap();

        assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    }
}
