package dev.pedrogomez.payaf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** El límite de pagos por IP de la demo pública, con un límite de 2 para no lanzar 30 pagos. */
@SpringBootTest(properties = {"payaf.reaper.enabled=false", "payaf.sim.webhooks.enabled=false",
        "payaf.demo.payments-per-minute=2"})
@AutoConfigureMockMvc
@ActiveProfiles("memory")
@DirtiesContext
class PaymentRateLimitTest {

    @Autowired
    private MockMvc mvc;

    private ResultActions pay(String ip) throws Exception {
        return mvc.perform(post("/api/payments").header("Idempotency-Key", "rl-" + UUID.randomUUID())
                .header("CF-Connecting-IP", ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"10.00\",\"currency\":\"EUR\",\"country\":\"FR\",\"merchantReference\":\"rl\"}"));
    }

    @Test
    @DisplayName("el tercer pago de la misma IP en un minuto es 429 RATE_LIMITED; otra IP y el resto de la API siguen")
    void limitsPaymentsPerIp() throws Exception {
        pay("203.0.113.7").andExpect(status().isCreated());
        pay("203.0.113.7").andExpect(status().isCreated());
        pay("203.0.113.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        pay("198.51.100.20").andExpect(status().isCreated());
        mvc.perform(get("/api/gateways").header("CF-Connecting-IP", "203.0.113.7")).andExpect(status().isOk());
        mvc.perform(post("/api/demo/reset").header("CF-Connecting-IP", "203.0.113.7")).andExpect(status().isOk());
    }
}
