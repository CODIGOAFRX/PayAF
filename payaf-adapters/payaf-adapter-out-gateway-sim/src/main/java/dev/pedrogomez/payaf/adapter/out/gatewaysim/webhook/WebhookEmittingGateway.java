package dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook;

import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.Objects;

/**
 * Decorador de una pasarela simulada que, como haría la PSP real, emite un webhook firmado cuando
 * una captura o un reembolso salen bien. Así el adaptador de webhooks recibe tráfico de verdad:
 * la misma operación llega dos veces, una por la respuesta síncrona y otra por la notificación, y
 * la aplicación tiene que reconocerla (ALREADY_IN_STATE).
 * <p>
 * El webhook se entrega al {@link WebhookSink} y se olvida; si fallara al construirse, no afecta a
 * la respuesta de la operación (el contrato de {@link PaymentGateway} prohíbe lanzar).
 */
public final class WebhookEmittingGateway implements PaymentGateway {

    private final PaymentGateway delegate;
    private final PspWebhookFormat format;
    private final WebhookSink sink;

    public WebhookEmittingGateway(PaymentGateway delegate, PspWebhookFormat format, WebhookSink sink) {
        this.delegate = Objects.requireNonNull(delegate);
        this.format = Objects.requireNonNull(format);
        this.sink = Objects.requireNonNull(sink);
    }

    @Override
    public GatewayId id() {
        return delegate.id();
    }

    @Override
    public GatewayResponse authorize(GatewayAuthorization authorization) {
        return delegate.authorize(authorization);
    }

    @Override
    public GatewayResponse capture(String gatewayReference, Money amount) {
        GatewayResponse response = delegate.capture(gatewayReference, amount);
        if (response.isApproved()) {
            emit(() -> format.captured(gatewayReference, response.gatewayReference().orElseThrow(), amount));
        }
        return response;
    }

    @Override
    public GatewayResponse refund(String gatewayReference, Money amount) {
        GatewayResponse response = delegate.refund(gatewayReference, amount);
        if (response.isApproved()) {
            emit(() -> format.refunded(gatewayReference, response.gatewayReference().orElseThrow(), amount));
        }
        return response;
    }

    @Override
    public GatewayResponse cancel(String gatewayReference) {
        return delegate.cancel(gatewayReference);
    }

    private void emit(java.util.function.Supplier<SimulatedWebhook> webhook) {
        try {
            sink.enqueue(webhook.get());
        } catch (RuntimeException e) {
            // Un webhook que no se puede emitir no estropea la operación, que ya se ha hecho.
        }
    }
}
