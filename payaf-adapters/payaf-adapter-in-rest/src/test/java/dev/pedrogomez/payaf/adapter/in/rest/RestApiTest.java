package dev.pedrogomez.payaf.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pedrogomez.payaf.application.exception.ApplicationException;
import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.in.AuthorizationResult;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentCommand;
import dev.pedrogomez.payaf.application.port.in.AuthorizePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CancelPaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.CapturePaymentUseCase;
import dev.pedrogomez.payaf.application.port.in.GatewayHealthView;
import dev.pedrogomez.payaf.application.port.in.PaymentView;
import dev.pedrogomez.payaf.application.port.in.QueryGatewayHealthUseCase;
import dev.pedrogomez.payaf.application.port.in.QueryPaymentsUseCase;
import dev.pedrogomez.payaf.application.port.in.RefundPaymentUseCase;
import dev.pedrogomez.payaf.domain.payment.AttemptOutcome;
import dev.pedrogomez.payaf.domain.payment.GatewayAttempt;
import dev.pedrogomez.payaf.domain.payment.IdempotencyKey;
import dev.pedrogomez.payaf.domain.payment.IllegalPaymentTransitionException;
import dev.pedrogomez.payaf.domain.payment.InconsistentPaymentStateException;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.routing.CircuitBreakerSnapshot;
import dev.pedrogomez.payaf.domain.routing.CircuitState;
import dev.pedrogomez.payaf.domain.routing.GatewayProfile;
import dev.pedrogomez.payaf.domain.routing.RejectionReason;
import dev.pedrogomez.payaf.domain.routing.RoutingCandidate;
import dev.pedrogomez.payaf.domain.routing.RoutingDecision;
import dev.pedrogomez.payaf.domain.routing.RoutingRejection;
import dev.pedrogomez.payaf.domain.shared.CountryCode;
import dev.pedrogomez.payaf.domain.shared.Currency;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import dev.pedrogomez.payaf.domain.shared.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import org.springframework.test.web.servlet.MvcResult;

/** La API REST con MockMvc: cada endpoint y cada mapeo de error, con los puertos simulados. */
@WebMvcTest
class RestApiTest {

    static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    static final PaymentId ID = PaymentId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    static final GatewayId STRIPE = GatewayId.of("stripe");

    @SpringBootApplication
    static class App {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AuthorizePaymentUseCase authorize;
    @MockitoBean
    private CapturePaymentUseCase capture;
    @MockitoBean
    private CancelPaymentUseCase cancel;
    @MockitoBean
    private RefundPaymentUseCase refund;
    @MockitoBean
    private QueryPaymentsUseCase query;
    @MockitoBean
    private QueryGatewayHealthUseCase health;
    @MockitoBean
    private ChaosAdmin chaos;
    @MockitoBean
    private EventFeed feed;

    private static final GatewayAttempt ATTEMPT = new GatewayAttempt(STRIPE, AttemptOutcome.APPROVED,
            Duration.ofMillis(42), Optional.empty(), Optional.of("pi_1"), NOW);

    static PaymentView view(PaymentStatus status) {
        Money amount = Money.parse("12.34", Currency.EUR);
        return new PaymentView(ID, amount, CountryCode.of("ES"), "pedido-1", status, Optional.of(STRIPE),
                Optional.of("pi_1"), Optional.empty(), Money.zero(Currency.EUR), amount, List.of(), List.of(ATTEMPT),
                NOW, NOW);
    }

    private static final String BODY = """
            {"amount": "12.34", "currency": "EUR", "country": "ES", "merchantReference": "pedido-1"}""";

    @Test
    @DisplayName("POST /api/payments: 201 con pago, enrutado (comisiones y descartes) e intentos")
    void createPayment() throws Exception {
        RoutingDecision decision = new RoutingDecision(
                List.of(new RoutingCandidate(STRIPE, Money.parse("0.61", Currency.EUR), CircuitState.CLOSED)),
                List.of(new RoutingRejection(GatewayId.of("redsys"), RejectionReason.CIRCUIT_OPEN)));
        when(authorize.authorize(any())).thenReturn(new AuthorizationResult(view(PaymentStatus.AUTHORIZED), decision,
                List.of(ATTEMPT), false));

        mvc.perform(post("/api/payments").header("Idempotency-Key", "K1").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/payments/" + ID))
                .andExpect(header().doesNotExist(PaymentController.REPLAYED_HEADER))
                .andExpect(jsonPath("$.payment.id").value(ID.toString()))
                .andExpect(jsonPath("$.payment.amount.amount").value("12.34"))
                .andExpect(jsonPath("$.payment.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.routing.candidates[0].gateway").value("stripe"))
                .andExpect(jsonPath("$.routing.candidates[0].estimatedFee.amount").value("0.61"))
                .andExpect(jsonPath("$.routing.rejections[0].reason").value("CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.attempts[0].outcome").value("APPROVED"))
                .andExpect(jsonPath("$.attempts[0].latencyMs").value(42));

        ArgumentCaptor<AuthorizePaymentCommand> captor = ArgumentCaptor.forClass(AuthorizePaymentCommand.class);
        verify(authorize).authorize(captor.capture());
        assertThat(captor.getValue().request().amount()).isEqualTo(Money.of(1234, Currency.EUR));
        assertThat(captor.getValue().request().key()).isEqualTo(IdempotencyKey.of("K1"));
    }

    @Test
    @DisplayName("POST /api/payments repetido: 200 con Idempotent-Replayed: true")
    void replay() throws Exception {
        when(authorize.authorize(any())).thenReturn(new AuthorizationResult(view(PaymentStatus.AUTHORIZED),
                new RoutingDecision(List.of(), List.of()), List.of(ATTEMPT), true));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "K1").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentController.REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    @DisplayName("errores de entrada: sin Idempotency-Key 400, JSON roto 400, campo que falta 400, valor inválido 422")
    void inputErrors() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                        .content("{roto"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"1.00\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BODY"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("12.34", "12.345")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        mvc.perform(post("/api/payments").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("EUR", "XXX")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        mvc.perform(get("/api/payments/no-es-un-uuid"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
    }

    @Test
    @DisplayName("capture, cancel, refunds, get y la lista")
    void operations() throws Exception {
        when(capture.capture(ID)).thenReturn(view(PaymentStatus.CAPTURED));
        when(cancel.cancel(ID)).thenReturn(view(PaymentStatus.CANCELED));
        when(query.get(ID)).thenReturn(view(PaymentStatus.CAPTURED));
        when(refund.refund(eq(ID), any())).thenReturn(view(PaymentStatus.PARTIALLY_REFUNDED));
        when(query.recent(anyInt())).thenReturn(List.of(view(PaymentStatus.CAPTURED)));

        mvc.perform(post("/api/payments/" + ID + "/capture")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        mvc.perform(post("/api/payments/" + ID + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        mvc.perform(post("/api/payments/" + ID + "/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"2.00\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));
        verify(refund).refund(ID, Money.of(200, Currency.EUR));
        mvc.perform(get("/api/payments/" + ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantReference").value("pedido-1"))
                .andExpect(jsonPath("$.gatewayReference").value("pi_1"));
        mvc.perform(get("/api/payments?limit=500")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID.toString()));
        verify(query).recent(PaymentController.MAX_LIMIT);
    }

    @Test
    @DisplayName("mapeo de errores de dominio y aplicación a ProblemDetail con code estable")
    void errorMapping() throws Exception {
        Object[][] cases = {
                {new IllegalPaymentTransitionException(ID, PaymentStatus.FAILED, PaymentStatus.CAPTURED), 422,
                        "ILLEGAL_TRANSITION"},
                {new InconsistentPaymentStateException("corrupto"), 500, "INCONSISTENT_STATE"},
                {ApplicationException.paymentNotFound(ID), 404, "PAYMENT_NOT_FOUND"},
                {ApplicationException.idempotencyConflict(IdempotencyKey.of("K")), 409, "IDEMPOTENCY_CONFLICT"},
                {ApplicationException.idempotencyInProgress(IdempotencyKey.of("K"), ID), 409, "IDEMPOTENCY_IN_PROGRESS"},
                {new ConcurrentPaymentModificationException(ID), 409, "CONCURRENT_MODIFICATION"},
                {ApplicationException.gatewayOperationFailed("capture", ID, STRIPE, AttemptOutcome.ERROR,
                        Optional.of("HTTP_503")), 502, "GATEWAY_OPERATION_FAILED"},
                {ApplicationException.operationUnrecorded("capture", ID, STRIPE, "pi_1", 3), 500, "OPERATION_UNRECORDED"},
                {ApplicationException.authorizationUnrecorded(ID, STRIPE, "pi_1"), 500, "AUTHORIZATION_UNRECORDED"},
        };
        for (Object[] c : cases) {
            doThrow((RuntimeException) c[0]).when(capture).capture(ID);
            mvc.perform(post("/api/payments/" + ID + "/capture"))
                    .andExpect(status().is((int) c[1]))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value((String) c[2]))
                    .andExpect(jsonPath("$.status").value((int) c[1]));
        }
    }

    @Test
    @DisplayName("GET /api/gateways: perfil y snapshot del breaker")
    void gateways() throws Exception {
        GatewayProfile profile = new GatewayProfile(STRIPE, Set.of(Currency.EUR, Currency.USD), Set.of(), 290,
                Map.of(Currency.EUR, 25L), true);
        CircuitBreakerSnapshot snapshot = new CircuitBreakerSnapshot(STRIPE, CircuitState.OPEN, 3, Optional.of(NOW),
                Optional.of(NOW.plusSeconds(15)), 0);
        when(health.health(NOW)).thenReturn(List.of(new GatewayHealthView(profile, snapshot)));
        mvc.perform(get("/api/gateways")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("stripe"))
                .andExpect(jsonPath("$[0].currencies[0]").value("EUR"))
                .andExpect(jsonPath("$[0].fixedFeeMinor.EUR").value(25))
                .andExpect(jsonPath("$[0].circuit.state").value("OPEN"))
                .andExpect(jsonPath("$[0].circuit.retryAt").value("2026-11-27T10:00:15Z"));
    }

    @Test
    @DisplayName("chaos: GET/PUT/DELETE, habilitar pasarelas, desconocida 404 y valores inválidos 422")
    void chaosEndpoints() throws Exception {
        when(chaos.get("redsys")).thenReturn(new ChaosAdmin.ChaosSettings(false, 0, 0));
        when(chaos.set(eq("redsys"), any())).thenAnswer(inv -> inv.getArgument(1));
        when(chaos.reset("redsys")).thenReturn(new ChaosAdmin.ChaosSettings(false, 0, 0));
        mvc.perform(get("/api/chaos/redsys")).andExpect(status().isOk()).andExpect(jsonPath("$.down").value(false));
        mvc.perform(put("/api/chaos/redsys").contentType(MediaType.APPLICATION_JSON).content("{\"down\": true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.down").value(true));
        verify(chaos).set("redsys", new ChaosAdmin.ChaosSettings(true, 0, 0.0));
        mvc.perform(delete("/api/chaos/redsys")).andExpect(status().isOk());
        mvc.perform(put("/api/gateways/redsys/enabled").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": false}")).andExpect(status().isOk());
        verify(chaos).setEnabled("redsys", false);

        when(chaos.get("paypal")).thenThrow(new ChaosAdmin.UnknownGatewayException("paypal"));
        mvc.perform(get("/api/chaos/paypal")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GATEWAY_NOT_FOUND"));
        when(chaos.set(eq("stripe"), any())).thenThrow(new IllegalArgumentException("tasa fuera de rango"));
        mvc.perform(put("/api/chaos/stripe").contentType(MediaType.APPLICATION_JSON).content("{\"errorRate\": 2}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
    }

    @Test
    @DisplayName("GET /api/events/stream reenvía los eventos del EventFeed por SSE")
    void sse() throws Exception {
        AtomicReference<Consumer<EventFeed.FeedEvent>> listener = new AtomicReference<>();
        when(feed.subscribe(any())).thenAnswer(inv -> {
            listener.set(inv.getArgument(0));
            return (AutoCloseable) () -> {
            };
        });
        MvcResult result = mvc.perform(get("/api/events/stream")).andExpect(request().asyncStarted()).andReturn();
        listener.get().accept(new EventFeed.FeedEvent(7, "PaymentAuthorized", "payment", ID.toString(), NOW,
                Map.of("gateway", "stripe")));
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("id:7").contains("event:PaymentAuthorized").contains("\"gateway\":\"stripe\"");
    }
}
