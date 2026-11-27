package dev.pedrogomez.payaf.application.port.out;

import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;

/**
 * Puerto de salida (driven): una pasarela de pago (PSP) concreta.
 * <p>
 * Es el puerto más «hexagonal» de PayAF: la aplicación orquesta el failover entre
 * pasarelas sin saber si al otro lado hay Stripe, Adyen o el simulador. Cada adaptador
 * traduce estas cuatro operaciones al protocolo de su PSP.
 * <p>
 * <strong>Contrato: el adaptador NUNCA lanza excepciones por fallos técnicos.</strong> Un
 * error de red, un HTTP 5xx o una respuesta ilegible se devuelven como
 * {@link dev.pedrogomez.payaf.domain.payment.AttemptOutcome#ERROR}, y agotar el tiempo de
 * espera como {@link dev.pedrogomez.payaf.domain.payment.AttemptOutcome#TIMEOUT}. Así el caso
 * de uso puede registrar el intento, alimentar el circuit breaker y pasar a la siguiente
 * pasarela sin {@code try/catch} por todas partes. Un rechazo del emisor es
 * {@link dev.pedrogomez.payaf.domain.payment.AttemptOutcome#DECLINED}, nunca un error.
 * <p>
 * Nunca se llama con una transacción de base de datos abierta (ver
 * {@code AuthorizePaymentService}).
 */
public interface PaymentGateway {

    /** Pasarela a la que habla este adaptador. Coincide con su {@code GatewayProfile}. */
    GatewayId id();

    /** Pide reservar el importe. Si sale APPROVED, la respuesta trae la referencia de la operación. */
    GatewayResponse authorize(GatewayAuthorization authorization);

    /** Cobra el total de una autorización previa de esta misma pasarela. */
    GatewayResponse capture(String gatewayReference, Money amount);

    /**
     * Devuelve parte o todo lo capturado en esta pasarela. Si sale APPROVED, la
     * {@code gatewayReference} de la respuesta es la referencia <em>del reembolso</em> en la PSP
     * (obligatoria): es la que llegará después en su notificación y la que impide contarlo dos veces.
     */
    GatewayResponse refund(String gatewayReference, Money amount);

    /** Anula una autorización no capturada. */
    GatewayResponse cancel(String gatewayReference);
}
