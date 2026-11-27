package dev.pedrogomez.payaf.adapter.out.gatewaysim;

import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Piezas comunes de los tests del simulador: un sleeper que no duerme y un Random con guion. */
public final class SimFixtures {

    private SimFixtures() {
    }

    /** Apunta cuánto se habría dormido, sin dormir. */
    public static final class RecordingSleeper implements Sleeper {

        private final List<Duration> sleeps = new CopyOnWriteArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
        }

        public List<Duration> sleeps() {
            return List.copyOf(sleeps);
        }

        public Duration total() {
            return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        }
    }

    /** {@link Random} cuyo {@code nextDouble} devuelve los valores que se le pongan, en orden. */
    public static final class ScriptedRandom extends Random {

        private final Deque<Double> doubles = new ArrayDeque<>();

        public ScriptedRandom(double... values) {
            super(42);
            for (double value : values) {
                doubles.add(value);
            }
        }

        @Override
        public double nextDouble() {
            Double next = doubles.poll();
            if (next == null) {
                throw new AssertionError("nextDouble() sin valor preparado");
            }
            return next;
        }
    }

    public static GatewayAuthorization authorization(long amountMinor, Currency currency) {
        return new GatewayAuthorization(PaymentId.of(UUID.randomUUID()), Money.of(amountMinor, currency),
                CountryCode.of("ES"), "pedido-1");
    }

    public static GatewayAuthorization authorization(long amountMinor) {
        return authorization(amountMinor, Currency.EUR);
    }

    public static Money eur(long amountMinor) {
        return Money.of(amountMinor, Currency.EUR);
    }

    /** Inyector sin averías que no duerme. */
    public static FaultInjector quietInjector() {
        return new FaultInjector(new Random(1), new RecordingSleeper());
    }
}
