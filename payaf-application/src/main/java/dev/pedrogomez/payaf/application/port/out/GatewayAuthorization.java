package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.util.Objects;

/**
 * Lo que se le manda a una pasarela para pedir una autorización.
 * <p>
 * Es un tipo propio del puerto, y no el agregado {@link Payment}, para que el adaptador no
 * pueda tocar el pago y para que reciba solo lo que la PSP necesita. El {@link PaymentId}
 * viaja como referencia nuestra: el adaptador puede usarlo como clave de idempotencia ante
 * la PSP, de modo que un reintento del mismo pago en la misma pasarela no autorice dos veces.
 *
 * @param paymentId         nuestro identificador del pago
 * @param amount            importe a autorizar
 * @param country           país del pago
 * @param merchantReference referencia del comercio
 */
public record GatewayAuthorization(PaymentId paymentId, Money amount, CountryCode country, String merchantReference) {

    public GatewayAuthorization {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(country);
        Objects.requireNonNull(merchantReference);
    }

    /** La petición de autorización de un pago. */
    public static GatewayAuthorization of(Payment payment) {
        return new GatewayAuthorization(payment.id(), payment.amount(), payment.request().country(),
                payment.request().merchantReference());
    }
}
