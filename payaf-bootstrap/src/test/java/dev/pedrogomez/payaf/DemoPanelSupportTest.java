package dev.pedrogomez.payaf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.trace.PortTraceFeed;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El soporte del panel en vivo, de punta a punta por HTTP: la traza de puertos con su
 * {@code X-Request-Id}, los datos de la demo y «Restaurar demo».
 */
@SpringBootTest(properties = {"payaf.reaper.enabled=false", "payaf.sim.webhooks.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("memory")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DemoPanelSupportTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PortTraceFeed traceFeed;

    private void pay(String key, String requestId) throws Exception {
        var request = post("/api/payments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"40.00\",\"currency\":\"EUR\",\"country\":\"ES\",\"merchantReference\":\"" + key + "\"}");
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        mvc.perform(request).andExpect(status().is2xxSuccessful());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(OutboxMessage m) {
        return m.event().payload();
    }

    private static List<Map<String, Object>> waitForDriving(List<OutboxMessage> received) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (received.stream().anyMatch(m -> "driving".equals(payload(m).get("side")))) {
                break;
            }
            Thread.sleep(20);
        }
        return received.stream().map(DemoPanelSupportTest::payload).toList();
    }

    @Test
    @DisplayName("un pago por la REST emite la traza de puertos con el X-Request-Id del cliente, y el driving cierra la traza")
    void paymentEmitsPortTrace() throws Exception {
        List<OutboxMessage> received = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = traceFeed.subscribe(received::add)) {
            mvc.perform(post("/api/payments").header("Idempotency-Key", "trace-1").header("X-Request-Id", "panel-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"40.00\",\"currency\":\"EUR\",\"country\":\"ES\",\"merchantReference\":\"t\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-Request-Id", "panel-123"));
            List<Map<String, Object>> touches = waitForDriving(received);

            assertThat(touches).isNotEmpty().allSatisfy(t -> assertThat(t.get("correlationId")).isEqualTo("panel-123"));
            Map<String, Object> last = touches.getLast();
            assertThat(last).containsEntry("side", "driving").containsEntry("adapter", "rest")
                    .containsEntry("port", "AuthorizePaymentUseCase").containsEntry("operation", "authorize")
                    .containsEntry("outcome", "AUTHORIZED").containsEntry("seq", 0);
            assertThat(touches).anySatisfy(t -> assertThat(t).containsEntry("port", "PaymentGateway")
                    .containsEntry("adapter", "redsys").containsEntry("outcome", "APPROVED"));
            assertThat(touches).anySatisfy(t -> assertThat(t).containsEntry("port", "IdempotencyStore")
                    .containsEntry("adapter", "memory").containsEntry("outcome", "Reserved"));
            assertThat(touches).anySatisfy(t -> assertThat(t).containsEntry("port", "EventPublisher")
                    .containsEntry("adapter", "outbox"));
            assertThat(touches.stream().map(t -> t.get("seq")).distinct().count()).isEqualTo(touches.size());
        }
    }

    @Test
    @DisplayName("el replay solo toca la idempotencia y el repositorio: ninguna pasarela")
    void replayTouchesNoGateway() throws Exception {
        pay("replay-1", null);
        List<OutboxMessage> received = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = traceFeed.subscribe(received::add)) {
            pay("replay-1", "replay-req");
            List<Map<String, Object>> touches = waitForDriving(received);
            assertThat(touches.getLast()).containsEntry("outcome", "REPLAYED");
            assertThat(touches).noneSatisfy(t -> assertThat(t.get("port")).isEqualTo("PaymentGateway"));
            assertThat(touches).anySatisfy(t -> assertThat(t).containsEntry("port", "IdempotencyStore")
                    .containsEntry("outcome", "AlreadyCompleted"));
        }
    }

    @Test
    @DisplayName("sin X-Request-Id se genera uno; las consultas del panel no emiten traza")
    void requestIdIsGeneratedAndQueriesAreSilent() throws Exception {
        List<OutboxMessage> received = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = traceFeed.subscribe(received::add)) {
            mvc.perform(get("/api/gateways"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Request-Id", Matchers.matchesPattern("[0-9a-f-]{36}")));
            mvc.perform(get("/api/gateways").header("X-Request-Id", "no vale con espacios"))
                    .andExpect(header().string("X-Request-Id", Matchers.not("no vale con espacios")));
            Thread.sleep(200);
            assertThat(received).isEmpty();
        }
    }

    @Test
    @DisplayName("GET /api/demo cuenta la persistencia activa, el timeout, el breaker y el límite")
    void demoInfo() throws Exception {
        mvc.perform(get("/api/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persistence").value("memory"))
                .andExpect(jsonPath("$.gatewayTimeoutMs").value(2000))
                .andExpect(jsonPath("$.failureThreshold").value(3))
                .andExpect(jsonPath("$.openDurationMs").value(15000))
                .andExpect(jsonPath("$.paymentsPerMinute").value(30));
    }

    @Test
    @DisplayName("Restaurar demo: quita las averías, habilita todas y deja los breakers en CLOSED")
    void resetDemo() throws Exception {
        mvc.perform(put("/api/chaos/redsys").contentType(MediaType.APPLICATION_JSON).content("{\"down\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/chaos/stripe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latencyMs\":500,\"errorRate\":0.5}")).andExpect(status().isOk());
        mvc.perform(put("/api/gateways/mock/enabled").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        for (int i = 0; i < 3; i++) {
            pay("reset-" + UUID.randomUUID(), null);
        }
        mvc.perform(get("/api/gateways"))
                .andExpect(jsonPath("$[?(@.id=='redsys')].circuit.state").value("OPEN"))
                .andExpect(jsonPath("$[?(@.id=='mock')].enabled").value(false));

        mvc.perform(post("/api/demo/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].enabled", Matchers.everyItem(Matchers.is(true))))
                .andExpect(jsonPath("$[*].circuit.state", Matchers.everyItem(Matchers.is("CLOSED"))))
                .andExpect(jsonPath("$[*].circuit.consecutiveFailures", Matchers.everyItem(Matchers.is(0))));
        mvc.perform(get("/api/chaos/redsys")).andExpect(jsonPath("$.down").value(false));
        mvc.perform(get("/api/chaos/stripe"))
                .andExpect(jsonPath("$.latencyMs").value(0))
                .andExpect(jsonPath("$.errorRate").value(0.0));

        // Redsys vuelve a cobrar a la primera.
        mvc.perform(post("/api/payments").header("Idempotency-Key", "after-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"40.00\",\"currency\":\"EUR\",\"country\":\"ES\",\"merchantReference\":\"r\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.authorizedBy").value("redsys"))
                .andExpect(jsonPath("$.attempts.length()").value(1));
    }
}
