package dev.pedrogomez.payaf.adapter.out.gatewaysim.redsys;

import static dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspSignatures.quote;

import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspSignatures;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.PspWebhookFormat;
import dev.pedrogomez.payaf.adapter.out.gatewaysim.webhook.SimulatedWebhook;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Notificación online de Redsys: un formulario {@code application/x-www-form-urlencoded} con tres
 * campos, {@code Ds_SignatureVersion=HMAC_SHA256_V1}, {@code Ds_MerchantParameters} (el JSON de la
 * operación en Base64) y {@code Ds_Signature}, que es el HMAC-SHA256 de {@code Ds_MerchantParameters}
 * en Base64 URL-safe.
 * <p>
 * Simplificación: el Redsys real deriva una clave por pedido cifrando el {@code Ds_Order} con 3DES
 * antes de calcular el HMAC; aquí se usa el secreto tal cual.
 * <ul>
 *   <li>Captura (confirmación): {@code Ds_TransactionType = 2}, {@code Ds_Response = 0900}.</li>
 *   <li>Devolución: {@code Ds_TransactionType = 3}, {@code Ds_Response = 0900} y el
 *       {@code Ds_AuthorisationCode} que, unido al pedido, forma la referencia del reembolso
 *       ({@code Ds_Order-Ds_AuthorisationCode}, ver {@link RedsysPaymentGateway}).</li>
 * </ul>
 */
public final class RedsysWebhooks implements PspWebhookFormat {

    public static final String SIGNATURE_VERSION = "HMAC_SHA256_V1";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private final String secret;
    private final Clock clock;

    public RedsysWebhooks(String secret, Clock clock) {
        this.secret = Objects.requireNonNull(secret);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SimulatedWebhook captured(String paymentReference, String operationReference, Money amount) {
        return notification(paymentReference, RedsysClient.CONFIRMATION, "", amount);
    }

    @Override
    public SimulatedWebhook refunded(String paymentReference, String refundReference, Money amount) {
        int dash = refundReference.indexOf('-');
        String authorisationCode = dash < 0 ? "" : refundReference.substring(dash + 1);
        return notification(paymentReference, RedsysClient.REFUND, authorisationCode, amount);
    }

    private SimulatedWebhook notification(String order, String transactionType, String authorisationCode,
                                          Money amount) {
        ZonedDateTime now = clock.instant().atZone(MADRID);
        String parameters = "{\"Ds_Date\":" + quote(DATE.format(now)) + ",\"Ds_Hour\":" + quote(HOUR.format(now))
                + ",\"Ds_Amount\":" + quote(Long.toString(amount.amountMinor()))
                + ",\"Ds_Currency\":" + quote(RedsysPaymentGateway.numericCurrency(amount.currency()))
                + ",\"Ds_Order\":" + quote(order)
                + ",\"Ds_MerchantCode\":" + quote(RedsysPaymentGateway.MERCHANT_CODE)
                + ",\"Ds_Terminal\":" + quote(RedsysPaymentGateway.TERMINAL)
                + ",\"Ds_Response\":\"0900\""
                + ",\"Ds_TransactionType\":" + quote(transactionType)
                + ",\"Ds_SecurePayment\":\"0\""
                + ",\"Ds_AuthorisationCode\":" + quote(authorisationCode) + "}";
        String merchantParameters = Base64.getEncoder().encodeToString(parameters.getBytes(StandardCharsets.UTF_8));
        String body = "Ds_SignatureVersion=" + SIGNATURE_VERSION
                + "&Ds_MerchantParameters=" + URLEncoder.encode(merchantParameters, StandardCharsets.UTF_8)
                + "&Ds_Signature=" + URLEncoder.encode(signature(secret, merchantParameters), StandardCharsets.UTF_8);
        return new SimulatedWebhook("redsys", "application/x-www-form-urlencoded", Map.of(), body);
    }

    /** El {@code Ds_Signature} de esos {@code Ds_MerchantParameters}. */
    public static String signature(String secret, String merchantParameters) {
        return Base64.getUrlEncoder().encodeToString(PspSignatures.hmacSha256(secret, merchantParameters));
    }
}
