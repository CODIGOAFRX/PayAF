package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.domain.routing.CircuitBreakerConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contexto mínimo para probar el adaptador contra un PostgreSQL de verdad (Testcontainers): la
 * autoconfiguración de JPA y Flyway, y los adaptadores construidos a mano como hace bootstrap.
 */
@SpringBootApplication
class JpaTestApplication {

    static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.of(3, Duration.ofSeconds(15));

    @Bean
    EntityManager sharedEntityManager(EntityManagerFactory emf) {
        return SharedEntityManagerCreator.createSharedEntityManager(emf);
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    JpaPaymentRepository paymentRepository(EntityManager em) {
        return new JpaPaymentRepository(em);
    }

    @Bean
    JdbcIdempotencyStore idempotencyStore(JdbcTemplate jdbc) {
        return new JdbcIdempotencyStore(jdbc);
    }

    @Bean
    JdbcProcessedNotificationStore processedNotificationStore(JdbcTemplate jdbc) {
        return new JdbcProcessedNotificationStore(jdbc);
    }

    @Bean
    OutboxEventPublisher eventPublisher(JdbcTemplate jdbc, JsonMapper json) {
        return new OutboxEventPublisher(jdbc, json);
    }

    @Bean
    JdbcOutboxReader outboxReader(JdbcTemplate jdbc, JsonMapper json) {
        return new JdbcOutboxReader(jdbc, json);
    }

    @Bean
    SpringTransactionRunner transactionRunner(PlatformTransactionManager tm) {
        return new SpringTransactionRunner(new TransactionTemplate(tm));
    }

    /** Un único contenedor para todas las clases de test que comparten este contexto. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17");
        }
    }
}
