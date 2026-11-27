package dev.pedrogomez.payaf.adapter.out.gatewaysim.mock;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.mock.MockPsp.MockReply;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.Objects;

/**
 * Adaptador del puerto {@link PaymentGateway} para la PSP de pruebas ({@link MockPsp}).
 * <p>
 * La traducción es mínima, pero existe igual que en las demás: {@code OK} → APPROVED con la
 * referencia, {@code KO} → DECLINED con el motivo, cualquier otra cosa → ERROR. Todas las
 * llamadas pasan por el {@link FaultInjector}. Nunca lanza.
 */
public final class MockPaymentGateway implements PaymentGateway {

    private final GatewayId id;
    private final MockPsp psp;
    private final FaultInjector faults;

    public MockPaymentGateway(GatewayId id, MockPsp psp, FaultInjector faults) {
        this.id = Objects.requireNonNull(id);
        this.psp = Objects.requireNonNull(psp);
        this.faults = Objects.requireNonNull(faults);
    }

    @Override
    public GatewayId id() {
        return id;
    }

    @Override
    public GatewayResponse authorize(GatewayAuthorization authorization) {
        Money amount = authorization.amount();
        return faults.call(() -> translate(psp.authorize(amount.amountMinor(), amount.currency().name())));
    }

    @Override
    public GatewayResponse capture(String gatewayReference, Money amount) {
        return faults.call(() -> translate(psp.capture(gatewayReference, amount.amountMinor(),
                amount.currency().name())));
    }

    @Override
    public GatewayResponse refund(String gatewayReference, Money amount) {
        return faults.call(() -> translate(psp.refund(gatewayReference, amount.amountMinor(),
                amount.currency().name())));
    }

    @Override
    public GatewayResponse cancel(String gatewayReference) {
        return faults.call(() -> translate(psp.cancel(gatewayReference)));
    }

    static GatewayResponse translate(MockReply reply) {
        if (MockPsp.OK.equals(reply.status()) && reply.reference() != null) {
            return GatewayResponse.approved(reply.reference(), Duration.ZERO);
        }
        if (MockPsp.KO.equals(reply.status())) {
            return GatewayResponse.declined(reply.reason(), Duration.ZERO);
        }
        return GatewayResponse.error("mock_unexpected_status:" + reply.status(), Duration.ZERO);
    }
}
