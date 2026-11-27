package dev.pedrogomez.payaf.adapter.in.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.in.GatewayNotification;
import dev.pedrogomez.payaf.application.port.in.HandleGatewayNotificationUseCase;
import dev.pedrogomez.payaf.application.port.in.NotificationResult;
import dev.pedrogomez.payaf.application.port.in.NotificationType;
import dev.pedrogomez.payaf.domain.payment.IllegalPaymentTransitionException;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * El adaptador de webhooks con MockMvc: firma válida e inválida por PSP, el timestamp caducado de
 * Stripe y el mapeo de resultados. Las firmas de los tests se calculan aquí con
 * {@code javax.crypto.Mac}, sin usar la clase del adaptador.
 */
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    static final String STRIPE_SECRET = "whsec_test";
    static final String ADYEN_SECRET = "adyen_test";
    static final String REDSYS_SECRET = "redsys_test";

    @SpringBootApplication
    static class App {

        @Bean
        StripeWebhookParser stripe() {
            return new StripeWebhookParser(STRIPE_SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        AdyenWebhookParser adyen() {
            return new AdyenWebhookParser(ADYEN_SECRET);
        }

        @Bean
        RedsysWebhookParser redsys() {
            return new RedsysWebhookParser(REDSYS_SECRET);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HandleGatewayNotificationUseCase useCase;

    private static byte[] hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static final String STRIPE_CAPTURE = "{\"id\":\"evt_1\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\","
            + "\"created\":" + NOW.getEpochSecond() + ",\"data\":{\"object\":{\"id\":\"pi_123\",\"amount\":1234,"
            + "\"currency\":\"eur\",\"status\":\"succeeded\"}}}";

    private ResultActions stripe(String body, long t, String secret) throws Exception {
        String v1 = HexFormat.of().formatHex(hmac(secret, t + "." + body));
        return mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=" + t + ",v1=" + v1).content(body));
    }

    @Test
    @DisplayName("Stripe con firma válida llega a la aplicación como CAPTURE_CONFIRMED")
    void stripeValid() throws Exception {
        when(useCase.handle(any())).thenReturn(NotificationResult.APPLIED);
        stripe(STRIPE_CAPTURE, NOW.getEpochSecond(), STRIPE_SECRET)
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0]").value("APPLIED"));
        ArgumentCaptor<GatewayNotification> captor = ArgumentCaptor.forClass(GatewayNotification.class);
        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.CAPTURE_CONFIRMED);
        assertThat(captor.getValue().gatewayReference()).isEqualTo("pi_123");
        assertThat(captor.getValue().notificationId()).isEqualTo("evt_1");
    }

    @Test
    @DisplayName("Stripe con firma de otro secreto, sin cabecera o caducada (más de 5 min): 401 y no llega")
    void stripeInvalid() throws Exception {
        stripe(STRIPE_CAPTURE, NOW.getEpochSecond(), "otro").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(STRIPE_CAPTURE))
                .andExpect(status().isUnauthorized());
        stripe(STRIPE_CAPTURE, NOW.minusSeconds(301).getEpochSecond(), STRIPE_SECRET)
                .andExpect(status().isUnauthorized());
        // Cuerpo alterado tras firmar
        String v1 = HexFormat.of().formatHex(hmac(STRIPE_SECRET, NOW.getEpochSecond() + "." + STRIPE_CAPTURE));
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=" + NOW.getEpochSecond() + ",v1=" + v1)
                        .content(STRIPE_CAPTURE.replace("1234", "9999")))
                .andExpect(status().isUnauthorized());
        verify(useCase, never()).handle(any());
    }

    @Test
    @DisplayName("Stripe dentro de la tolerancia (4 min) y un reembolso: REFUND_CONFIRMED con importe y referencia")
    void stripeRefund() throws Exception {
        when(useCase.handle(any())).thenReturn(NotificationResult.ALREADY_IN_STATE);
        String body = "{\"id\":\"evt_2\",\"type\":\"refund.created\",\"created\":1,\"data\":{\"object\":{"
                + "\"id\":\"re_9\",\"payment_intent\":\"pi_123\",\"amount\":500,\"currency\":\"eur\",\"status\":\"succeeded\"}}}";
        stripe(body, NOW.minusSeconds(240).getEpochSecond(), STRIPE_SECRET)
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0]").value("ALREADY_IN_STATE"));
        ArgumentCaptor<GatewayNotification> captor = ArgumentCaptor.forClass(GatewayNotification.class);
        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().amount()).contains(Money.of(500, Currency.EUR));
        assertThat(captor.getValue().gatewayRefundReference()).contains("re_9");
    }

    private static String adyenBody(String hmac) {
        return "{\"live\":\"false\",\"notificationItems\":[{\"NotificationRequestItem\":{"
                + "\"additionalData\":{\"hmacSignature\":\"" + hmac + "\"},"
                + "\"amount\":{\"currency\":\"EUR\",\"value\":1000},\"eventCode\":\"REFUND\","
                + "\"eventDate\":\"2026-11-27T10:00:00Z\",\"merchantAccountCode\":\"PayAFECOM\","
                + "\"merchantReference\":\"\",\"originalReference\":\"8815000000000001\","
                + "\"pspReference\":\"8815000000000002\",\"success\":\"true\"}}]}";
    }

    @Test
    @DisplayName("Adyen: hmacSignature válida → REFUND_CONFIRMED; inválida → 401")
    void adyen() throws Exception {
        when(useCase.handle(any())).thenReturn(NotificationResult.UNKNOWN_PAYMENT);
        String signed = "8815000000000002:8815000000000001:PayAFECOM::1000:EUR:REFUND:true";
        String good = Base64.getEncoder().encodeToString(hmac(ADYEN_SECRET, signed));
        mvc.perform(post("/webhooks/adyen").contentType(MediaType.APPLICATION_JSON).content(adyenBody(good)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0]").value("UNKNOWN_PAYMENT"));
        ArgumentCaptor<GatewayNotification> captor = ArgumentCaptor.forClass(GatewayNotification.class);
        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().gatewayReference()).isEqualTo("8815000000000001");
        assertThat(captor.getValue().gatewayRefundReference()).contains("8815000000000002");

        String bad = Base64.getEncoder().encodeToString(hmac("otro", signed));
        mvc.perform(post("/webhooks/adyen").contentType(MediaType.APPLICATION_JSON).content(adyenBody(bad)))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions redsys(String params, String secret) throws Exception {
        String merchantParameters = Base64.getEncoder().encodeToString(params.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().encodeToString(hmac(secret, merchantParameters));
        String body = "Ds_SignatureVersion=HMAC_SHA256_V1&Ds_MerchantParameters="
                + URLEncoder.encode(merchantParameters, StandardCharsets.UTF_8)
                + "&Ds_Signature=" + URLEncoder.encode(signature, StandardCharsets.UTF_8);
        return mvc.perform(post("/webhooks/redsys").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body));
    }

    @Test
    @DisplayName("Redsys: Ds_Signature válida → CAPTURE_CONFIRMED con el Ds_Order; inválida → 401")
    void redsys() throws Exception {
        when(useCase.handle(any())).thenReturn(NotificationResult.DUPLICATE);
        String params = "{\"Ds_Date\":\"27/11/2026\",\"Ds_Hour\":\"11:00\",\"Ds_Amount\":\"1234\",\"Ds_Currency\":\"978\","
                + "\"Ds_Order\":\"1234ABCDEFGH\",\"Ds_Response\":\"0900\",\"Ds_TransactionType\":\"2\","
                + "\"Ds_AuthorisationCode\":\"\"}";
        redsys(params, REDSYS_SECRET).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0]").value("DUPLICATE"));
        ArgumentCaptor<GatewayNotification> captor = ArgumentCaptor.forClass(GatewayNotification.class);
        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().gatewayReference()).isEqualTo("1234ABCDEFGH");
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.CAPTURE_CONFIRMED);

        redsys(params, "otro").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("una DomainException o un conflicto de versión dan 409 para que la PSP reintente")
    void conflicts() throws Exception {
        PaymentId id = PaymentId.of(UUID.randomUUID());
        when(useCase.handle(any())).thenThrow(new IllegalPaymentTransitionException(id, PaymentStatus.FAILED,
                PaymentStatus.CAPTURED));
        stripe(STRIPE_CAPTURE, NOW.getEpochSecond(), STRIPE_SECRET).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));

        doThrow(new ConcurrentPaymentModificationException(id)).when(useCase).handle(any());
        stripe(STRIPE_CAPTURE, NOW.getEpochSecond(), STRIPE_SECRET).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("eventos que PayAF no usa: 200 IGNORED sin llamar a la aplicación; PSP desconocida: 404")
    void ignoredAndUnknown() throws Exception {
        String body = "{\"id\":\"evt_3\",\"type\":\"customer.created\",\"created\":1,\"data\":{\"object\":{}}}";
        stripe(body, NOW.getEpochSecond(), STRIPE_SECRET).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0]").value("IGNORED"));
        verify(useCase, never()).handle(any());
        mvc.perform(post("/webhooks/paypal").content("{}")).andExpect(status().isNotFound());
    }
}
