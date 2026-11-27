package dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.FaultInjector;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.References;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient.RedsysRequest;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys.RedsysClient.RedsysResponse;
import dev.pedrogomez.payaf.application.port.out.GatewayAuthorization;
import dev.pedrogomez.payaf.application.port.out.GatewayResponse;
import dev.pedrogomez.payaf.application.port.out.PaymentGateway;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * Adaptador del puerto {@link PaymentGateway} para Redsys: traduce entre el idioma de la
 * aplicación y el del TPV virtual ({@link RedsysClient}).
 * <p>
 * Traducción (capa anticorrupción):
 * <ul>
 *   <li>Cada operación es una petición con su {@code Ds_Merchant_TransactionType}
 *       ({@code 1}, {@code 2}, {@code 3} o {@code 9}). Los importes van en céntimos como texto y
 *       la divisa con su código numérico ISO 4217 ({@link #numericCurrency}).</li>
 *   <li>El número de pedido lo pone el comercio: aquí se genera uno nuevo por autorización (4
 *       dígitos y 8 alfanuméricos) y es la referencia que guarda el pago.</li>
 *   <li>{@code Ds_Response} {@code 0000}-{@code 0099}, y {@code 0900}/{@code 0400} en
 *       confirmaciones, devoluciones y anulaciones → APPROVED; {@code 0100}-{@code 0999} (como
 *       {@code 0190}) → DECLINED con ese código; {@code 9xxx} o algo ilegible → ERROR.</li>
 *   <li>Un {@code errorCode} {@code SISxxxx} → DECLINED con ese código: Redsys ha funcionado y ha
 *       rechazado la petición (confirmar dos veces da {@code SIS0063}).</li>
 *   <li>La referencia de una autorización, captura o anulación es el {@code Ds_Order}. Redsys no da
 *       identificador propio a una devolución, así que su referencia es
 *       {@code Ds_Order-Ds_AuthorisationCode} (el simulador no repite códigos dentro de un
 *       pedido).</li>
 * </ul>
 * Una anulación se manda con importe {@code 0}: el puerto no lo da y el simulador no lo
 * comprueba. Todas las llamadas pasan por el {@link FaultInjector}. Nunca lanza.
 */
public final class RedsysPaymentGateway implements PaymentGateway {

    /** Código de comercio (FUC) y terminal de pruebas. */
    static final String MERCHANT_CODE = "999008881";
    static final String TERMINAL = "1";

    private final GatewayId id;
    private final RedsysClient client;
    private final FaultInjector faults;
    private final Random random;

    public RedsysPaymentGateway(GatewayId id, RedsysClient client, FaultInjector faults, Random random) {
        this.id = Objects.requireNonNull(id);
        this.client = Objects.requireNonNull(client);
        this.faults = Objects.requireNonNull(faults);
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public GatewayId id() {
        return id;
    }

    @Override
    public GatewayResponse authorize(GatewayAuthorization authorization) {
        return faults.call(() -> {
            String order = References.digits(random, 4, false) + References.upperAlphanumeric(random, 8);
            return translate(client.process(request(order, authorization.amount(), RedsysClient.PREAUTHORIZATION)),
                    false);
        });
    }

    @Override
    public GatewayResponse capture(String gatewayReference, Money amount) {
        return faults.call(() -> translate(client.process(
                request(gatewayReference, amount, RedsysClient.CONFIRMATION)), false));
    }

    @Override
    public GatewayResponse refund(String gatewayReference, Money amount) {
        return faults.call(() -> translate(client.process(
                request(gatewayReference, amount, RedsysClient.REFUND)), true));
    }

    @Override
    public GatewayResponse cancel(String gatewayReference) {
        return faults.call(() -> translate(client.process(new RedsysRequest(gatewayReference, "0", "978",
                RedsysClient.CANCELLATION, MERCHANT_CODE, TERMINAL)), false));
    }

    /**
     * Traduce una respuesta de Redsys.
     *
     * @param refund si es una devolución: su referencia lleva además el código de autorización
     */
    static GatewayResponse translate(RedsysResponse response, boolean refund) {
        if (response.errorCode() != null && !response.errorCode().isBlank()) {
            return GatewayResponse.declined(response.errorCode(), Duration.ZERO);
        }
        String code = response.dsResponse();
        int value;
        try {
            value = Integer.parseInt(code);
        } catch (NumberFormatException | NullPointerException e) {
            return GatewayResponse.error("redsys_unreadable_response:" + code, Duration.ZERO);
        }
        boolean operationAccepted = (value == 900 || value == 400)
                && !RedsysClient.PREAUTHORIZATION.equals(response.dsTransactionType());
        if ((value >= 0 && value <= 99) || operationAccepted) {
            String reference = refund
                    ? response.dsOrder() + "-" + response.dsAuthorisationCode()
                    : response.dsOrder();
            return GatewayResponse.approved(reference, Duration.ZERO);
        }
        if (value >= 100 && value <= 999) {
            return GatewayResponse.declined(code, Duration.ZERO);
        }
        return GatewayResponse.error(code, Duration.ZERO);
    }

    /** Código numérico ISO 4217 de la divisa, que es como la pide Redsys. */
    static String numericCurrency(Currency currency) {
        return switch (currency) {
            case EUR -> "978";
            case USD -> "840";
            case GBP -> "826";
            case JPY -> "392";
        };
    }

    private static RedsysRequest request(String order, Money amount, String type) {
        return new RedsysRequest(order, Long.toString(amount.amountMinor()), numericCurrency(amount.currency()), type,
                MERCHANT_CODE, TERMINAL);
    }
}
