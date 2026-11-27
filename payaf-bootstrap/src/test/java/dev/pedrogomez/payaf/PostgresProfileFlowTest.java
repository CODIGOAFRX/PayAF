package dev.pedrogomez.payaf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.pedrogomez.payaf.adapter.out.persistencejpa.JpaPaymentRepository;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * El perfil {@code postgres} completo contra un PostgreSQL 17 de verdad (Testcontainers), por la API
 * REST: autorizar, repetir, capturar y reembolsar; los eventos quedan en el outbox y el relay los
 * marca como publicados.
 */
@SpringBootTest(properties = "payaf.reaper.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@Import(PostgresProfileFlowTest.Postgres.class)
class PostgresProfileFlowTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17");
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("autorizar, replay, capturar y reembolsar con JPA, Flyway y outbox")
    void fullFlow() throws Exception {
        assertThat(payments).isInstanceOf(JpaPaymentRepository.class);
        String key = "flujo-" + UUID.randomUUID();
        String body = """
                {"amount": "40.00", "currency": "EUR", "country": "ES", "merchantReference": "pedido-pg"}""";

        String created = mvc.perform(post("/api/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.payment.authorizedBy").value("redsys"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.payment.id");

        mvc.perform(post("/api/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.payment.id").value(id));

        mvc.perform(post("/api/payments/" + id + "/capture")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        mvc.perform(post("/api/payments/" + id + "/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": \"15.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refunded.amount").value("15.00"));

        mvc.perform(get("/api/payments/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.refunds.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].gateway").value("redsys"));

        assertThat(jdbc.queryForList("SELECT event_type FROM outbox WHERE aggregate_id = ? ORDER BY id",
                String.class, id)).containsSubsequence("PaymentCreated", "PaymentAuthorized", "PaymentCaptured",
                "PaymentRefunded");
        // El relay (cada 500 ms) acaba marcándolos todos como publicados
        long deadline = System.currentTimeMillis() + 10_000;
        Integer pending;
        do {
            Thread.sleep(200);
            pending = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class);
        } while (pending != null && pending > 0 && System.currentTimeMillis() < deadline);
        assertThat(pending).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM payments WHERE id = ?::uuid", Long.class, id))
                .isGreaterThanOrEqualTo(3);
    }
}
