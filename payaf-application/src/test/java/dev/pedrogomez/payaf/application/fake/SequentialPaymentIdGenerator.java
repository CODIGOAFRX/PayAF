package dev.pedrogomez.payaf.application.fake;

import dev.pedrogomez.payaf.application.port.out.PaymentIdGenerator;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import java.util.UUID;

/** Identificadores previsibles: 00000000-0000-0000-0000-000000000001, ...0002, etc. */
public final class SequentialPaymentIdGenerator implements PaymentIdGenerator {

    private long next = 1;

    @Override
    public synchronized PaymentId next() {
        return PaymentId.of(new UUID(0, next++));
    }
}
